package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Owns the native library lookup and the complete bridge symbol table. */
final class NativeLibrary implements AutoCloseable {
    final Arena arena;
    final MethodHandle abiVersion;
    final MethodHandle writeBuildInfo;
    final MethodHandle fillTestFrame;
    final MethodHandle createRenderer;
    final MethodHandle destroyRenderer;
    final MethodHandle writeRendererInfo;
    final MethodHandle queryCapabilities;
    final MethodHandle writeColorManagementInfo;
    final MethodHandle queryColorLut;
    final MethodHandle queryPassDescriptor;
    final MethodHandle applySettings;
    final MethodHandle queryDiagnostics;
    final MethodHandle bindVulkanInteropBuffer;
    final MethodHandle unbindVulkanInteropBuffer;
    final MethodHandle queryVulkanInteropState;
    final MethodHandle acquireVulkanInteropFrame;
    final MethodHandle releaseVulkanInteropFrame;
    final MethodHandle closeWin32Handle;
    final MethodHandle resetScene;
    final MethodHandle upsertSection;
    final MethodHandle removeSection;
    final MethodHandle commitScene;
    final MethodHandle updateCamera;
    final MethodHandle acquireFrame;
    final MethodHandle releaseFrame;
    final MethodHandle renderFrame;

    private NativeLibrary(Arena arena, SymbolLookup symbols) {
        this.arena = arena;
        Linker linker = Linker.nativeLinker();
        abiVersion = downcall(linker, symbols, Symbol.ABI_VERSION);
        writeBuildInfo = downcall(linker, symbols, Symbol.WRITE_BUILD_INFO);
        fillTestFrame = downcall(linker, symbols, Symbol.FILL_TEST_FRAME);
        createRenderer = downcall(linker, symbols, Symbol.CREATE_RENDERER);
        destroyRenderer = downcall(linker, symbols, Symbol.DESTROY_RENDERER);
        writeRendererInfo = downcall(linker, symbols, Symbol.WRITE_RENDERER_INFO);
        queryCapabilities = downcall(linker, symbols, Symbol.QUERY_CAPABILITIES);
        writeColorManagementInfo = downcall(
                linker, symbols, Symbol.WRITE_COLOR_MANAGEMENT_INFO);
        queryColorLut = downcall(linker, symbols, Symbol.QUERY_COLOR_LUT);
        queryPassDescriptor = downcall(linker, symbols, Symbol.QUERY_PASS_DESCRIPTOR);
        applySettings = downcall(linker, symbols, Symbol.APPLY_SETTINGS);
        queryDiagnostics = downcall(linker, symbols, Symbol.QUERY_DIAGNOSTICS);
        bindVulkanInteropBuffer = downcall(
                linker, symbols, Symbol.BIND_VULKAN_INTEROP_BUFFER);
        unbindVulkanInteropBuffer = downcall(
                linker, symbols, Symbol.UNBIND_VULKAN_INTEROP_BUFFER);
        queryVulkanInteropState = downcall(
                linker, symbols, Symbol.QUERY_VULKAN_INTEROP_STATE);
        acquireVulkanInteropFrame = downcall(
                linker, symbols, Symbol.ACQUIRE_VULKAN_INTEROP_FRAME);
        releaseVulkanInteropFrame = downcall(
                linker, symbols, Symbol.RELEASE_VULKAN_INTEROP_FRAME);
        closeWin32Handle = downcall(linker, symbols, Symbol.CLOSE_WIN32_HANDLE);
        resetScene = downcall(linker, symbols, Symbol.RESET_SCENE);
        upsertSection = downcall(linker, symbols, Symbol.UPSERT_SECTION);
        removeSection = downcall(linker, symbols, Symbol.REMOVE_SECTION);
        commitScene = downcall(linker, symbols, Symbol.COMMIT_SCENE);
        updateCamera = downcall(linker, symbols, Symbol.UPDATE_CAMERA);
        acquireFrame = downcall(linker, symbols, Symbol.ACQUIRE_FRAME);
        releaseFrame = downcall(linker, symbols, Symbol.RELEASE_FRAME);
        renderFrame = downcall(linker, symbols, Symbol.RENDER_FRAME);
    }

    static NativeLibrary open(Path libraryPath) {
        Arena arena = Arena.ofConfined();
        try {
            return new NativeLibrary(arena, SymbolLookup.libraryLookup(libraryPath, arena));
        } catch (Throwable error) {
            arena.close();
            throw error;
        }
    }

    private static MethodHandle downcall(
            Linker linker,
            SymbolLookup symbols,
            Symbol symbol) {
        return linker.downcallHandle(
                symbols.findOrThrow(symbol.externalName), symbol.descriptor);
    }

    @Override
    public void close() {
        arena.close();
    }

    enum Symbol {
        ABI_VERSION(
                "cycles_bridge_abi_version",
                FunctionDescriptor.of(JAVA_INT)),
        WRITE_BUILD_INFO(
                "cycles_bridge_write_build_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
        FILL_TEST_FRAME(
                "cycles_bridge_fill_test_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG)),
        CREATE_RENDERER(
                "cycles_bridge_create_renderer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS)),
        DESTROY_RENDERER(
                "cycles_bridge_destroy_renderer",
                FunctionDescriptor.ofVoid(ADDRESS)),
        WRITE_RENDERER_INFO(
                "cycles_bridge_write_renderer_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
        QUERY_CAPABILITIES(
                "cycles_bridge_query_capabilities",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        WRITE_COLOR_MANAGEMENT_INFO(
                "cycles_bridge_write_color_management_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
        QUERY_COLOR_LUT(
                "cycles_bridge_query_color_lut",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        ADDRESS, ADDRESS, JAVA_LONG)),
        QUERY_PASS_DESCRIPTOR(
                "cycles_bridge_query_pass_descriptor",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)),
        APPLY_SETTINGS(
                "cycles_bridge_apply_settings",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        QUERY_DIAGNOSTICS(
                "cycles_bridge_query_diagnostics",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        BIND_VULKAN_INTEROP_BUFFER(
                "cycles_bridge_bind_vulkan_interop_buffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        UNBIND_VULKAN_INTEROP_BUFFER(
                "cycles_bridge_unbind_vulkan_interop_buffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS)),
        QUERY_VULKAN_INTEROP_STATE(
                "cycles_bridge_query_vulkan_interop_state",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        ACQUIRE_VULKAN_INTEROP_FRAME(
                "cycles_bridge_acquire_vulkan_interop_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS)),
        RELEASE_VULKAN_INTEROP_FRAME(
                "cycles_bridge_release_vulkan_interop_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)),
        CLOSE_WIN32_HANDLE(
                "cycles_bridge_close_win32_handle",
                FunctionDescriptor.ofVoid(JAVA_LONG)),
        RESET_SCENE(
                "cycles_bridge_reset_scene",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        UPSERT_SECTION(
                "cycles_bridge_upsert_section",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        REMOVE_SECTION(
                "cycles_bridge_remove_section",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)),
        COMMIT_SCENE(
                "cycles_bridge_commit_scene",
                FunctionDescriptor.of(JAVA_INT, ADDRESS)),
        UPDATE_CAMERA(
                "cycles_bridge_update_camera",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
        ACQUIRE_FRAME(
                "cycles_bridge_acquire_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS)),
        RELEASE_FRAME(
                "cycles_bridge_release_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)),
        RENDER_FRAME(
                "cycles_bridge_render_frame",
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

        final String externalName;
        final FunctionDescriptor descriptor;

        Symbol(String externalName, FunctionDescriptor descriptor) {
            this.externalName = externalName;
            this.descriptor = descriptor;
        }
    }
}
