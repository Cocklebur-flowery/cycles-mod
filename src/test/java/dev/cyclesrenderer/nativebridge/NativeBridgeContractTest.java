package dev.cyclesrenderer.nativebridge;

import org.junit.jupiter.api.Test;

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NativeBridgeContractTest {
    private static final String EXPECTED_PUBLIC_SURFACE_SHA256 =
            "692745869890fae3afd109881a274ae29d4deeb24798d6e73964ac5a4b8c48c7";
    private static final String EXPECTED_LAYOUT_SHA256 =
            "d578c93283f841b1f794e406c537094858e397ebed6d9e520654dcda0b97a16f";

    @Test
    void publicFacadeRemainsStable() throws IllegalAccessException {
        assertEquals(43, NativeBridge.ABI_VERSION);
        assertEquals(8, NativeBridge.DEVICE_UPDATE_PHASE_COUNT);
        assertEquals(2, NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT);
        assertEquals(3, NativeBridge.PIXEL_FORMAT_RGBA32_FLOAT);

        String fingerprint = fingerprint(publicSurfaceLines());
        assertEquals(EXPECTED_PUBLIC_SURFACE_SHA256, fingerprint,
                "NativeBridge public surface changed: " + fingerprint);
    }

    @Test
    void nativeLayoutsKeepNamesOffsetsAndSizes() throws ReflectiveOperationException {
        List<String> layouts = new ArrayList<>();
        for (Field field : NativeLayouts.class.getDeclaredFields()) {
            if (field.getType() != MemoryLayout.class) {
                continue;
            }
            field.setAccessible(true);
            MemoryLayout layout = (MemoryLayout) field.get(null);
            layouts.add(layoutLine(field.getName(), layout));
        }
        layouts.sort(Comparator.naturalOrder());
        assertFalse(layouts.isEmpty());

        String fingerprint = fingerprint(layouts);
        assertEquals(EXPECTED_LAYOUT_SHA256, fingerprint,
                "NativeBridge memory layouts changed: " + fingerprint);
    }

    private static List<String> publicSurfaceLines() throws IllegalAccessException {
        List<String> lines = new ArrayList<>();
        for (Field field : NativeBridge.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                lines.add("field " + field.getName() + ":" + typeName(field.getType())
                        + "=" + field.get(null));
            }
        }
        for (Method method : NativeBridge.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                lines.add("method NativeBridge." + methodSignature(method));
            }
        }
        for (Class<?> nested : NativeBridge.class.getDeclaredClasses()) {
            if (!Modifier.isPublic(nested.getModifiers())) {
                continue;
            }
            lines.add("type " + nested.getSimpleName() + ":" + nested.getModifiers());
            for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    lines.add("ctor " + nested.getSimpleName() + parameters(constructor.getParameterTypes()));
                }
            }
            for (Method method : nested.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    lines.add("method " + nested.getSimpleName() + "." + methodSignature(method));
                }
            }
            RecordComponent[] components = nested.getRecordComponents();
            if (components != null) {
                for (RecordComponent component : components) {
                    lines.add("component " + nested.getSimpleName() + "." + component.getName()
                            + ":" + typeName(component.getType()));
                }
            }
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    private static String layoutLine(String fieldName, MemoryLayout layout) {
        StringBuilder line = new StringBuilder(fieldName)
                .append(" size=").append(layout.byteSize())
                .append(" align=").append(layout.byteAlignment());
        List<MemoryLayout> members = ((GroupLayout) layout).memberLayouts();
        for (MemoryLayout member : members) {
            String name = member.name().orElseThrow();
            line.append('|').append(name)
                    .append('@').append(layout.byteOffset(PathElement.groupElement(name)))
                    .append(':').append(member.byteSize())
                    .append('/').append(member.byteAlignment());
        }
        return line.toString();
    }

    private static String methodSignature(Method method) {
        return method.getName() + parameters(method.getParameterTypes())
                + ":" + typeName(method.getReturnType());
    }

    private static String parameters(Class<?>[] parameterTypes) {
        StringBuilder result = new StringBuilder("(");
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(typeName(parameterTypes[index]));
        }
        return result.append(')').toString();
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.componentType()) + "[]" : type.getName();
    }

    private static String fingerprint(List<String> lines) {
        String canonical = String.join("\n", lines) + "\n";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }
}
