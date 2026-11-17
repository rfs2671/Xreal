# Read-only: prints the active video signal Windows is sending to every connected display
# (QueryDisplayConfig), plus colour encoding / bits per channel. Changes nothing.
$src = @'
using System;
using System.Runtime.InteropServices;
public static class DispCfg {
    [StructLayout(LayoutKind.Sequential)] public struct LUID { public uint Low; public int High; }
    [StructLayout(LayoutKind.Sequential)] public struct RATIONAL { public uint Num; public uint Den; }
    [StructLayout(LayoutKind.Sequential)] public struct REGION { public uint cx; public uint cy; }
    [StructLayout(LayoutKind.Sequential)] public struct PATH_SOURCE { public LUID adapterId; public uint id; public uint modeInfoIdx; public uint statusFlags; }
    [StructLayout(LayoutKind.Sequential)] public struct PATH_TARGET {
        public LUID adapterId; public uint id; public uint modeInfoIdx; public uint outputTechnology; public uint rotation; public uint scaling;
        public RATIONAL refreshRate; public uint scanLineOrdering; public int targetAvailable; public uint statusFlags; }
    [StructLayout(LayoutKind.Sequential)] public struct PATH_INFO { public PATH_SOURCE source; public PATH_TARGET target; public uint flags; }
    [StructLayout(LayoutKind.Sequential)] public struct VIDEO_SIGNAL {
        public ulong pixelRate; public RATIONAL hSyncFreq; public RATIONAL vSyncFreq; public REGION activeSize; public REGION totalSize;
        public uint videoStandard; public uint scanLineOrdering; }
    [StructLayout(LayoutKind.Explicit, Size = 64)] public struct MODE_INFO {
        [FieldOffset(0)] public uint infoType; [FieldOffset(4)] public uint id; [FieldOffset(8)] public LUID adapterId;
        [FieldOffset(16)] public VIDEO_SIGNAL targetSignal; }
    [StructLayout(LayoutKind.Sequential)] public struct HEADER { public uint type; public uint size; public LUID adapterId; public uint id; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] public struct TARGET_NAME {
        public HEADER header; public uint flags; public uint outputTechnology; public ushort edidManufactureId; public ushort edidProductCodeId;
        public uint connectorInstance;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string monitorFriendlyDeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string monitorDevicePath; }
    [StructLayout(LayoutKind.Sequential)] public struct ADV_COLOR { public HEADER header; public uint value; public uint colorEncoding; public uint bitsPerColorChannel; }
    [DllImport("user32.dll")] public static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPaths, out uint numModes);
    [DllImport("user32.dll")] public static extern int QueryDisplayConfig(uint flags, ref uint numPaths, [Out] PATH_INFO[] paths, ref uint numModes, [Out] MODE_INFO[] modes, IntPtr topologyId);
    [DllImport("user32.dll")] public static extern int DisplayConfigGetDeviceInfo(ref TARGET_NAME info);
    [DllImport("user32.dll")] public static extern int DisplayConfigGetDeviceInfo(ref ADV_COLOR info);
    public static string Dump() {
        uint np, nm; var sb = new System.Text.StringBuilder();
        int r = GetDisplayConfigBufferSizes(2, out np, out nm); if (r != 0) return "GetDisplayConfigBufferSizes failed " + r;
        var paths = new PATH_INFO[np]; var modes = new MODE_INFO[nm];
        r = QueryDisplayConfig(2, ref np, paths, ref nm, modes, IntPtr.Zero); if (r != 0) return "QueryDisplayConfig failed " + r;
        string[] tech = { "other", "HD15", "S-Video", "composite", "component", "DVI", "HDMI", "LVDS", "D_JPN", "SDI", "DisplayPort external", "DisplayPort embedded", "UDI external", "UDI embedded", "SDTV dongle", "Miracast", "indirect wired", "indirect virtual" };
        string[] enc = { "RGB", "YCbCr 4:4:4", "YCbCr 4:2:2", "YCbCr 4:2:0", "intensity" };
        for (int i = 0; i < np; i++) {
            var t = paths[i].target;
            var n = new TARGET_NAME(); n.header.type = 2; n.header.size = (uint)Marshal.SizeOf(typeof(TARGET_NAME)); n.header.adapterId = t.adapterId; n.header.id = t.id;
            DisplayConfigGetDeviceInfo(ref n);
            var c = new ADV_COLOR(); c.header.type = 9; c.header.size = (uint)Marshal.SizeOf(typeof(ADV_COLOR)); c.header.adapterId = t.adapterId; c.header.id = t.id;
            int rc = DisplayConfigGetDeviceInfo(ref c);
            sb.AppendFormat("display '{0}'  connector: {1}\r\n", n.monitorFriendlyDeviceName, t.outputTechnology < tech.Length ? tech[t.outputTechnology] : ("0x" + t.outputTechnology.ToString("X")));
            if (t.modeInfoIdx < nm && modes[t.modeInfoIdx].infoType == 2) {
                var s = modes[t.modeInfoIdx].targetSignal;
                sb.AppendFormat("  active {0}x{1}  total {2}x{3}  pixel clock {4:F3} MHz  hsync {5:F3} kHz  vsync {6:F3} Hz  scan {7}\r\n",
                    s.activeSize.cx, s.activeSize.cy, s.totalSize.cx, s.totalSize.cy, s.pixelRate / 1e6,
                    s.hSyncFreq.Den == 0 ? 0 : s.hSyncFreq.Num / (double)s.hSyncFreq.Den / 1000.0, s.vSyncFreq.Den == 0 ? 0 : s.vSyncFreq.Num / (double)s.vSyncFreq.Den, s.scanLineOrdering);
            }
            if (rc == 0) sb.AppendFormat("  colour encoding {0}, {1} bits per channel, advanced-colour flags 0x{2:X}\r\n", c.colorEncoding < enc.Length ? enc[c.colorEncoding] : c.colorEncoding.ToString(), c.bitsPerColorChannel, c.value);
        }
        return sb.ToString();
    }
}
'@
Add-Type -TypeDefinition $src
[DispCfg]::Dump()
