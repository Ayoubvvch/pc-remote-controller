using System;
using System.Runtime.InteropServices;

namespace AudioVolume
{
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(IntPtr pNotify);
        [PreserveSig] int UnregisterControlChangeNotify(IntPtr pNotify);
        [PreserveSig] int GetChannelCount(out uint pnChannelCount);
        [PreserveSig] int SetMasterVolumeLevel(float fLevelDB, ref Guid pguidEventContext);
        [PreserveSig] int SetMasterVolumeLevelScalar(float fLevel, ref Guid pguidEventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float pfLevelDB);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float pfLevel);
        [PreserveSig] int SetChannelVolumeLevel(uint nChannel, float fLevelDB, ref Guid pguidEventContext);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint nChannel, float fLevel, ref Guid pguidEventContext);
        [PreserveSig] int GetChannelVolumeLevel(uint nChannel, out float pfLevelDB);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint nChannel, out float pfLevel);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, ref Guid pguidEventContext);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool pbMute);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid id, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object aev);
    }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorComObject { }

    class Program
    {
        static void Main(string[] args)
        {
            try
            {
                var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
                IMMDevice dev;
                // dataFlow: 0 = eRender, role: 1 = eMultimedia
                enumerator.GetDefaultAudioEndpoint(0, 1, out dev);
                
                var iid = typeof(IAudioEndpointVolume).GUID;
                object obj;
                dev.Activate(ref iid, 23, IntPtr.Zero, out obj);
                var epv = (IAudioEndpointVolume)obj;

                Guid ctx = Guid.Empty;

                if (args.Length > 0)
                {
                    string arg = args[0].ToLower().Trim();
                    if (arg == "mute")
                    {
                        bool isMuted;
                        epv.GetMute(out isMuted);
                        epv.SetMute(!isMuted, ref ctx);
                        Console.WriteLine("MUTED:" + (!isMuted));
                        return;
                    }

                    float val;
                    if (float.TryParse(arg, out val))
                    {
                        if (val > 1.0f) val = val / 100.0f;
                        if (val < 0.0f) val = 0.0f;
                        if (val > 1.0f) val = 1.0f;
                        epv.SetMasterVolumeLevelScalar(val, ref ctx);
                        epv.SetMute(false, ref ctx);
                        Console.WriteLine("SET:" + (int)Math.Round(val * 100));
                        return;
                    }
                }

                float current;
                epv.GetMasterVolumeLevelScalar(out current);
                Console.WriteLine("GET:" + (int)Math.Round(current * 100));
            }
            catch (Exception ex)
            {
                Console.WriteLine("ERR:" + ex.Message);
            }
        }
    }
}
