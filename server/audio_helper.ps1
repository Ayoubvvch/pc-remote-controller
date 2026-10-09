param(
    [Parameter(Position=0)]
    [float]$Volume = -1
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioEndpointVolume {
    int f(); int g(); int h(); int j();
    int SetMasterVolumeLevelScalar(float fLevel, System.Guid pguidEventContext);
    int i();
    int GetMasterVolumeLevelScalar(out float pfLevel);
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, System.Guid pguidEventContext);
    int GetMute(out bool pbMute);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDevice {
    int Activate(ref System.Guid id, int clsCtx, int activationParams, out IAudioEndpointVolume aev);
}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceEnumerator {
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject { }

public class AudioController {
    public static void SetVolume(float level) {
        var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
        IMMDevice dev = null;
        enumerator.GetDefaultAudioEndpoint(0, 1, out dev);
        var IID_IAudioEndpointVolume = typeof(IAudioEndpointVolume).GUID;
        IAudioEndpointVolume epv = null;
        dev.Activate(ref IID_IAudioEndpointVolume, 23, 0, out epv);
        epv.SetMasterVolumeLevelScalar(level, System.Guid.Empty);
    }
    public static float GetVolume() {
        var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
        IMMDevice dev = null;
        enumerator.GetDefaultAudioEndpoint(0, 1, out dev);
        var IID_IAudioEndpointVolume = typeof(IAudioEndpointVolume).GUID;
        IAudioEndpointVolume epv = null;
        dev.Activate(ref IID_IAudioEndpointVolume, 23, 0, out epv);
        float level = 0;
        epv.GetMasterVolumeLevelScalar(out level);
        return level;
    }
}
"@ -ErrorAction SilentlyContinue

if ($Volume -ge 0) {
    [AudioController]::SetVolume([float]($Volume / 100.0))
    Write-Output "OK:$Volume"
} else {
    $current = [AudioController]::GetVolume() * 100
    Write-Output "CURRENT:$([math]::Round($current))"
}
