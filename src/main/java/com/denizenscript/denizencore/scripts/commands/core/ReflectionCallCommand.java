package com.denizenscript.denizencore.scripts.commands.core;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.JavaReflectedObjectTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.*;
import com.denizenscript.denizencore.utilities.ReflectionHelper;
import com.denizenscript.denizencore.utilities.ReflectionRefuse;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.debugging.DebugInternals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Executable;
import java.lang.reflect.InaccessibleObjectException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public class ReflectionCallCommand extends AbstractCommand {

    public ReflectionCallCommand() {
        setName("reflectioncall");
        setSyntax("reflectioncall [name:<string>/constructor] [<reflected_object>] (params:<param>|...) (signature:<signature>)");
        setRequiredArguments(1, 3);
        autoCompile();
    }

    public static void autoExecute(ScriptEntry scriptEntry,
                                   @ArgName("on") @ArgLinear JavaReflectedObjectTag callOn,
                                   @ArgName("name") @ArgPrefixed @ArgDefaultNull String name,
                                   @ArgName("constructor") boolean constructor,
                                   @ArgName("params") @ArgPrefixed @ArgDefaultText("") ListTag params,
                                   @ArgName("signature") @ArgPrefixed @ArgDefaultNull ListTag signature) {
        if (name == null && !constructor) {
            throw new InvalidArgumentsRuntimeException("Must specify either a method name or 'constructor'.");
        }
        boolean isStatic;
        Class<?> callOnType;
        if (callOn.object instanceof Class<?> callOnClass) {
            isStatic = true;
            callOnType = callOnClass;
        }
        else {
            isStatic = false;
            callOnType = callOn.object.getClass();
        }
        Class<?>[] paramTypes;
        if (signature != null) {
            paramTypes = parseSignature(signature);
            if (paramTypes == null) {
                Debug.echoError("Invalid signature specified.");
                return;
            }
        }
        else {
            paramTypes = null;
        }
        Map<DenizenTypesKey, CachedMethod> classMethodCache = METHODS_CACHE.computeIfAbsent(callOnType, k -> new HashMap<>());
        CachedMethod cachedMethod;
        DenizenTypesKey typesKey;
        if (name != null) {
            if (paramTypes != null) {
                typesKey = new DenizenTypesKey(paramTypes, name);
                cachedMethod = classMethodCache.computeIfAbsent(typesKey, k -> {
                    Debug.log("Running signature lookup");
                    MethodHandle potentialMethod = ReflectionHelper.getMethodHandle(callOnType, name, paramTypes);
                    return potentialMethod != null ? new CachedMethod(potentialMethod.type().parameterArray(), potentialMethod) : null;
                });
            }
            else {
                typesKey = new DenizenTypesKey(params, name);
                cachedMethod = getOrFindFromCache(callOnType, classMethodCache, Class::getDeclaredMethods, check -> check.getName().equals(name), typesKey, params, MethodHandles.Lookup::unreflect);
            }
        }
        else {
            if (!isStatic) {
                Debug.echoError("Cannot construct from instance type: reflected object must be a class.");
                return;
            }
            if (paramTypes != null) {
                typesKey = DenizenTypesKey.ofConstructor(paramTypes);
                cachedMethod = classMethodCache.computeIfAbsent(typesKey, k -> {
                    Debug.log("Running signature lookup");
                    MethodHandle potentialConstructor = ReflectionHelper.getConstructor(callOnType, paramTypes);
                    return potentialConstructor != null ? new CachedMethod(potentialConstructor.type().parameterArray(), potentialConstructor) : null;
                });
            }
            else {
                typesKey = DenizenTypesKey.ofConstructor(params);
                cachedMethod = getOrFindFromCache(callOnType, classMethodCache, Class::getDeclaredConstructors, check -> true, typesKey, params, MethodHandles.Lookup::unreflectConstructor);
            }
        }
        if (cachedMethod == null) {
            Debug.echoError("Couldn't find matching " + (constructor ? "constructor" : "method") + '.');
            classMethodCache.put(typesKey, null); // explicitly mark as non-existent
            return;
        }
        int instanceParam = isStatic ? 0 : 1;
        Object[] convertedParams = new Object[params.size() + instanceParam];
        if (!tryConvertParms(cachedMethod.parameterTypes(), params, convertedParams, instanceParam, true)) {
            Debug.echoError("Failed to convert params.");
            return;
        }
        if (!isStatic) {
            convertedParams[0] = callOn.object;
        }
        try {
            Object result = cachedMethod.method().invokeWithArguments(convertedParams);
            if (result != null) {
                scriptEntry.saveObject("result", new JavaReflectedObjectTag(result));
            }
        }
        catch (Throwable e) {
            Debug.echoError("Failed to call method/constructor:");
            Debug.echoError(e);
            return;
        }
    }

    public static Class<?>[] parseSignature(ListTag list) {
        Class<?>[] signature = new Class[list.size()];
        int index = 0;
        for (ObjectTag object : list.objectForms) {
            if (object instanceof ElementTag element) {
                Class<?> clazz = ReflectionHelper.getClassOrPrimitive(element.asString());
                if (clazz == null) {
                    Debug.echoError("Invalid signature entry '" + object + "': class couldn't be found.");
                    return null;
                }
                signature[index] = clazz;
            }
            else if (object instanceof JavaReflectedObjectTag reflectedObjectTag) {
                if (!(reflectedObjectTag.object instanceof Class<?> clazz)) {
                    Debug.echoError("Invalid signature entry '" + object + "': must be a class.");
                    return null;
                }
                signature[index] = clazz;
            }
            index++;
        }
        return signature;
    }

    @FunctionalInterface
    public interface ToHandleFunction<T> {
        MethodHandle toHandle(MethodHandles.Lookup lookup, T reflected) throws IllegalAccessException;
    }

    public static <T extends Executable> CachedMethod getOrFindFromCache(Class<?> findOn, Map<DenizenTypesKey, CachedMethod> classCache, Function<Class<?>, T[]> allOptionsGetter, Predicate<T> filter, DenizenTypesKey typesKey, ListTag params, ToHandleFunction<T> toHandle) {
        int paramCount = params.size();
        Object[] convertedParams = new Object[paramCount];
        return classCache.computeIfAbsent(typesKey, k -> {
            Debug.log("Running lookup");
            for (T option : allOptionsGetter.apply(findOn)) {
                if (option.isAnnotationPresent(ReflectionRefuse.class) || !filter.test(option)) {
                    continue;
                }
                if (option.getParameterCount() != paramCount) {
                    continue;
                }
                Class<?>[] paramTypes = option.getParameterTypes();
                if (!tryConvertParms(paramTypes, params, convertedParams, 0, false)) {
                    continue;
                }
                try {
                    option.setAccessible(true);
                    return new CachedMethod(paramTypes, toHandle.toHandle(ReflectionHelper.LOOKUP, option));
                }
                catch (IllegalAccessException | InaccessibleObjectException e) {
                    Debug.echoError("Couldn't access:");
                    Debug.echoError(e);
                    return null;
                }
            }
            return null;
        });
    }

    static boolean tryConvertParms(Class<?>[] parameterTypes, ListTag denizenParams, Object[] javaParams, int startingIndex, boolean showErrors) {
        for (int i = startingIndex; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            ObjectTag denizenParam = denizenParams.getObject(i);
            Object convertedParam = ReflectionSetCommand.convertObjectTypeFor(parameterType, denizenParam, showErrors);
            if (convertedParam == null) {
                return false;
            }
            javaParams[i] = convertedParam;
        }
        return true;
    }

    public record DenizenTypesKey(List<Class<?>> denizenTypes, String name) {
        public static DenizenTypesKey ofConstructor(Class<?>[] types) {
            return new DenizenTypesKey(types, "<init>");
        }

        public static DenizenTypesKey ofConstructor(ListTag list) {
            return new DenizenTypesKey(list, "<init>");
        }

        public DenizenTypesKey(Class<?>[] types, String name) {
            this(Arrays.asList(types), name);
        }

        public DenizenTypesKey(ListTag list, String name) {
            this(new ArrayList<>(list.size()), name);
            for (ObjectTag object : list.objectForms) {
                denizenTypes.add(object.getDenizenObjectType().clazz);
            }
        }
    }

    public record CachedMethod(Class<?>[] parameterTypes, MethodHandle method) {}

    static final Map<Class<?>, Map<DenizenTypesKey, CachedMethod>> METHODS_CACHE = new HashMap<>();
}
