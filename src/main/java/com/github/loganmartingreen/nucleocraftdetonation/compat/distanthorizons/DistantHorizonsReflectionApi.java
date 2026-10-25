package com.github.loganmartingreen.nucleocraftdetonation.compat.distanthorizons;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import org.slf4j.Logger;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reflection-only bridge to the public Distant Horizons API.
 *
 * <p>No DH class is referenced in a method signature or field type. This keeps
 * Distant Horizons genuinely optional and prevents a missing DH installation
 * from causing class-loading failures.</p>
 */
final class DistantHorizonsReflectionApi {
    private static final String DH_API =
            "com.seibel.distanthorizons.api.DhApi";
    private static final String DH_DELAYED =
            "com.seibel.distanthorizons.api.DhApi$Delayed";
    private static final String DH_VEC3D =
            "com.seibel.distanthorizons.api.objects.math.DhApiVec3d";
    private static final String DH_BOX =
            "com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox";
    private static final String DH_MATERIAL =
            "com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial";

    private final Logger logger;
    private final Set<Object> overriddenConfigValues =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean permanentlyUnavailable;
    private boolean warned;
    private boolean ready;

    private Class<?> apiClass;
    private Class<?> delayedClass;
    private Class<?> vec3dClass;
    private Class<?> boxClass;
    private Class<?> materialClass;

    private Constructor<?> vec3dConstructor;
    private Constructor<?> boxConstructor;

    private Object configs;
    private Object terrainRepo;
    private Object worldProxy;
    private Object renderProxy;
    private Object customRenderFactory;
    private Object genericRenderingConfigValue;

    DistantHorizonsReflectionApi(Logger logger) {
        this.logger = logger;
    }

    boolean tryInitialize() {
        if (ready) {
            return true;
        }
        if (permanentlyUnavailable) {
            return false;
        }

        try {
            apiClass = Class.forName(DH_API);
            delayedClass = Class.forName(DH_DELAYED);
            vec3dClass = Class.forName(DH_VEC3D);
            boxClass = Class.forName(DH_BOX);
            materialClass = Class.forName(DH_MATERIAL);

            int apiMajor = ((Number) invokeStatic(apiClass, "getApiMajorVersion"))
                    .intValue();
            if (apiMajor < 3) {
                permanentlyUnavailable = true;
                warnOnce("Distant Horizons API " + apiMajor
                        + " is too old; NucleoCraft requires DH API 3 or newer.", null);
                return false;
            }

            vec3dConstructor = vec3dClass.getConstructor(
                    double.class,
                    double.class,
                    double.class
            );
            boxConstructor = boxClass.getConstructor(
                    vec3dClass,
                    vec3dClass,
                    Color.class,
                    materialClass
            );

            configs = getStaticField(delayedClass, "configs");
            terrainRepo = getStaticField(delayedClass, "terrainRepo");
            worldProxy = getStaticField(delayedClass, "worldProxy");
            renderProxy = getStaticField(delayedClass, "renderProxy");
            customRenderFactory = getStaticField(
                    delayedClass,
                    "customRenderObjectFactory"
            );

            ready = configs != null
                    && worldProxy != null
                    && customRenderFactory != null;
            return ready;
        } catch (ClassNotFoundException exception) {
            permanentlyUnavailable = true;
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Unable to initialise Distant Horizons compatibility.", exception);
            return false;
        }
    }

    Object findClientLevelWrapper(Object minecraftLevel) {
        if (!tryInitialize() || minecraftLevel == null) {
            return null;
        }

        try {
            Object allLevels = invoke(worldProxy, "getAllLoadedLevelWrappers");
            if (allLevels instanceof Iterable<?> iterable) {
                for (Object wrapper : iterable) {
                    Object wrappedLevel = invokeOptional(
                            wrapper,
                            "getWrappedMcObject"
                    );
                    if (wrappedLevel == minecraftLevel) {
                        return wrapper;
                    }
                }
            }

            Object singlePlayer = invokeOptional(
                    worldProxy,
                    "getSinglePlayerLevel"
            );
            if (singlePlayer != null) {
                return singlePlayer;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Unable to resolve the Distant Horizons level wrapper.", exception);
        }

        return null;
    }

    void enableGenericRendering() {
        if (!tryInitialize()
                || genericRenderingConfigValue != null
                && overriddenConfigValues.contains(genericRenderingConfigValue)) {
            return;
        }

        try {
            Object graphics = invoke(configs, "graphics");
            Object generic = invoke(graphics, "genericRendering");
            genericRenderingConfigValue = invokeOptional(
                    generic,
                    "renderingEnabled"
            );
            overrideConfig(genericRenderingConfigValue, true);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Unable to enable Distant Horizons generic rendering.", exception);
        }
    }

    void clearGenericRenderingOverride() {
        if (genericRenderingConfigValue == null) {
            return;
        }

        clearOverride(genericRenderingConfigValue);
        genericRenderingConfigValue = null;
    }

    void clearConfigOverrides() {
        List<Object> values = new ArrayList<>(overriddenConfigValues);
        for (Object value : values) {
            clearOverride(value);
        }
        genericRenderingConfigValue = null;
    }

    private void clearOverride(Object value) {
        overriddenConfigValues.remove(value);
        try {
            invokeOptional(value, "clearValue");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            /* DH may already be unloading. */
        }
    }

    Object createBox(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            Color colour,
            boolean illuminated
    ) throws ReflectiveOperationException {
        Object min = vec3dConstructor.newInstance(minX, minY, minZ);
        Object max = vec3dConstructor.newInstance(maxX, maxY, maxZ);
        Object material = enumValue(
                DH_MATERIAL,
                illuminated ? "ILLUMINATED" : "UNKNOWN"
        );

        return boxConstructor.newInstance(
                min,
                max,
                colour,
                material
        );
    }

    Object createAndRegisterGroup(
            Object levelWrapper,
            String resourceLocation,
            double originX,
            double originY,
            double originZ,
            List<Object> boxes,
            boolean illuminated,
            boolean initiallyActive
    ) {
        if (!tryInitialize() || levelWrapper == null || boxes.isEmpty()) {
            return null;
        }

        try {
            Object register = invoke(levelWrapper, "getRenderRegister");
            if (register == null) {
                return null;
            }

            Object origin = vec3dConstructor.newInstance(originX, originY, originZ);
            Object group = invoke(
                    customRenderFactory,
                    "createRelativePositionedGroup",
                    resourceLocation,
                    origin,
                    boxes
            );

            invokeOptional(group, "setSkyLight", 15);
            invokeOptional(group, "setBlockLight", illuminated ? 15 : 3);
            invokeOptional(group, "setSsaoEnabled", false);
            invokeOptional(group, "setActive", initiallyActive);
            invoke(register, "add", group);

            return new RegisteredGroup(register, group);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Unable to register a Distant Horizons cloud model.", exception);
            return null;
        }
    }

    void setGroupActive(Object registeredGroup, boolean active) {
        if (!(registeredGroup instanceof RegisteredGroup group)) {
            return;
        }

        try {
            invokeOptional(group.group, "setActive", active);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            /* The renderer may be rebuilding during a dimension change. */
        }
    }

    void removeGroup(Object registeredGroup) {
        if (!(registeredGroup instanceof RegisteredGroup group)) {
            return;
        }

        try {
            Object id = invoke(group.group, "getId");
            invoke(group.register, "remove", id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            /* DH may already have removed the level's renderer. */
        }
    }

    boolean overwriteChunkData(
            Object levelWrapper,
            Object minecraftLevel,
            Iterable<?> chunks
    ) {
        if (!tryInitialize()
                || terrainRepo == null
                || levelWrapper == null
                || minecraftLevel == null) {
            return false;
        }

        boolean submittedAny = false;

        try {
            /*
             * DH expects one Object[] per chunk containing the vanilla chunk
             * object followed by its ClientLevel/ServerLevel. Passing several
             * chunks inside one array can cause DH to reject the data and stop
             * updating its renderer on non-flat terrain.
             */
            for (Object chunk : chunks) {
                if (chunk == null) {
                    continue;
                }

                invoke(
                        terrainRepo,
                        "overwriteChunkDataAsync",
                        levelWrapper,
                        new Object[]{chunk, minecraftLevel}
                );
                submittedAny = true;
            }

            return submittedAny;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Unable to submit changed chunks to Distant Horizons.", exception);
            return false;
        }
    }

    private void overrideConfig(
            Object configValue,
            Object value
    ) throws ReflectiveOperationException {
        if (configValue == null || value == null) {
            return;
        }

        invoke(configValue, "setValue", value);
        overriddenConfigValues.add(configValue);
    }

    private static Object enumValue(
            String className,
            String name
    ) throws ReflectiveOperationException {
        Class<?> enumClass = Class.forName(className);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object value = Enum.valueOf((Class<? extends Enum>) enumClass, name);
        return value;
    }

    private static Object getStaticField(
            Class<?> type,
            String name
    ) throws ReflectiveOperationException {
        Field field = type.getField(name);
        return field.get(null);
    }

    private static Object invokeStatic(
            Class<?> type,
            String methodName,
            Object... arguments
    ) throws ReflectiveOperationException {
        Method method = findMethod(type, methodName, arguments);
        return method.invoke(null, arguments);
    }

    private static Object invoke(
            Object target,
            String methodName,
            Object... arguments
    ) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }

        Method method = findMethod(
                target.getClass(),
                methodName,
                arguments
        );
        return method.invoke(target, arguments);
    }

    private static Object invokeOptional(
            Object target,
            String methodName,
            Object... arguments
    ) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }

        try {
            return invoke(target, methodName, arguments);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findMethod(
            Class<?> type,
            String name,
            Object[] arguments
    ) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != arguments.length) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;

            for (int index = 0; index < parameterTypes.length; index++) {
                Object argument = arguments[index];
                if (argument == null) {
                    continue;
                }

                Class<?> expected = wrapPrimitive(parameterTypes[index]);
                if (!expected.isAssignableFrom(argument.getClass())) {
                    compatible = false;
                    break;
                }
            }

            if (compatible) {
                return method;
            }
        }

        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private void warnOnce(String message, Throwable throwable) {
        if (warned) {
            return;
        }

        warned = true;
        if (throwable == null) {
            logger.warn(message);
        } else {
            logger.warn(message, throwable);
        }
    }

    private record RegisteredGroup(
            Object register,
            Object group
    ) {
    }
}
