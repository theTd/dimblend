package dimblend.radio.acoustics;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.ptr.PointerByReference;

public final class SteamGpu {
    public interface Api extends SteamAudio.Api {
        int iplOpenCLDeviceListCreate(Pointer context, DeviceSettings settings, PointerByReference list);
        int iplOpenCLDeviceListGetNumDevices(Pointer list);
        void iplOpenCLDeviceListGetDeviceDesc(Pointer list, int index, DeviceDesc desc);
        void iplOpenCLDeviceListRelease(PointerByReference list);
        int iplOpenCLDeviceCreate(Pointer context, Pointer list, int index, PointerByReference device);
        void iplOpenCLDeviceRelease(PointerByReference device);
        int iplRadeonRaysDeviceCreate(Pointer openCL, Pointer settings, PointerByReference device);
        void iplRadeonRaysDeviceRelease(PointerByReference device);
        int iplStaticMeshCreate(Pointer scene, MeshSettings settings, PointerByReference mesh);
        void iplStaticMeshAdd(Pointer mesh, Pointer scene);
        void iplStaticMeshRemove(Pointer mesh, Pointer scene);
        void iplStaticMeshRelease(PointerByReference mesh);
    }
    @FieldOrder({"type", "reserve", "fraction", "requiresTan"})
    public static class DeviceSettings extends Structure { public int type = 2, reserve; public float fraction; public int requiresTan; }
    @FieldOrder({"platform", "platformName", "platformVendor", "platformVersion", "device", "name", "vendor", "version", "type", "convolution", "update", "granularity", "score"})
    public static class DeviceDesc extends Structure {
        public Pointer platform, platformName, platformVendor, platformVersion, device, name, vendor, version;
        public int type, convolution, update, granularity;
        public float score;
    }
    @FieldOrder({"vertices", "triangles", "materials", "vertexData", "triangleData", "materialIndices", "materialData"})
    public static class MeshSettings extends Structure {
        public int vertices, triangles, materials;
        public Pointer vertexData, triangleData, materialIndices, materialData;
    }

    private static Api api;
    public static synchronized Api api() {
        if (api == null) {
            SteamAudio.api();
            // Preloaded by path: phonon.dll's LoadLibrary("GPUUtilities.dll") then resolves to this module.
            Native.load(SteamNativeLibraries.library("GPUUtilities.dll").toString(), com.sun.jna.Library.class);
            api = Native.load(SteamAudio.library().toString(), Api.class);
        }
        return api;
    }
    private SteamGpu() { }
}
