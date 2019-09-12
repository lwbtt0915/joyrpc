package io.joyrpc.util;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.exception.CreationException;
import io.joyrpc.exception.MethodOverloadException;
import io.joyrpc.exception.ReflectionException;

import java.lang.reflect.*;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Title: 类型转换工具类<br>
 * <p/>
 * Description: 调用端时将类描述转换为字符串传输。服务端将字符串转换为具体的类<br>
 * <pre>
 *     保证传递的时候值为可阅读格式，而不是jvm格式（[Lxxx;）：
 *         普通：java.lang.String、java.lang.String[]
 *         基本类型：int、int[]
 *         匿名类：io.joyrpc.Xxx$1、io.joyrpc.Xxx$1[]
 *         本地类：io.joyrpc.Xxx$1Local、io.joyrpc.Xxx$1Local[]
 *         成员类：io.joyrpc.Xxx$Member、io.joyrpc.Xxx$Member[]
 *         内部类：io.joyrpc.Inner、io.joyrpc.Inner[]
 *     同时Class.forName的时候又会解析出Class。
 *     </pre>
 * <p>
 */
public class ClassUtils {

    /**
     * 分静态和透明字段
     */
    public final static Predicate<Field> NONE_STATIC_TRANSIENT = (o) -> {
        int mod = o.getModifiers();
        return !Modifier.isStatic(mod) && !Modifier.isTransient(mod);
    };

    /**
     * 判断是否是基本类型
     */
    public final static Predicate<Class> PRIMITIVE = (o) -> {
        Class target = o;
        if (o.isArray()) { // 数组，检查数组类型
            target = o.getComponentType();
        }
        return target.isPrimitive() // 基本类型
                || Boolean.class.isAssignableFrom(target)
                || Character.class.isAssignableFrom(target)
                || Number.class.isAssignableFrom(target)
                || String.class.isAssignableFrom(target)
                || Date.class.isAssignableFrom(target);
    };

    protected final static Map<String, Class> forNames = new ConcurrentHashMap(5000);

    /**
     * String-->Class 缓存，指定大小
     */
    protected final static Map<String, Class> nameTypes = new ConcurrentHashMap(5000);

    /**
     * String-->Class 缓存，指定大小
     */
    protected final static Map<Class, String> typeNames = new ConcurrentHashMap(5000);

    /**
     * 类的元数据
     */
    protected final static Map<Class<?>, ClassMeta> classMetas = new ConcurrentHashMap<>();

    static {
        //这些类型不能用类加载器加载
        nameTypes.put("void", void.class);
        nameTypes.put("boolean", boolean.class);
        nameTypes.put("byte", byte.class);
        nameTypes.put("char", char.class);
        nameTypes.put("double", double.class);
        nameTypes.put("float", float.class);
        nameTypes.put("int", int.class);
        nameTypes.put("long", long.class);
        nameTypes.put("short", short.class);
        forNames.putAll(nameTypes);
    }

    /**
     * 是否是Java内置的类
     *
     * @param clazz
     * @return
     */
    public static boolean isJavaClass(final Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (clazz.isPrimitive()) {
            return true;
        }
        String name = clazz.getName();
        int length = name.length();
        return length >= 5 && name.startsWith("java") && (name.charAt(4) == '.' || length >= 6 && name.startsWith("x.", 4));
    }


    /**
     * 是否默认基本类型，基本类型+String+Date+Number+Boolean+Character
     *
     * @param clazz the cls
     * @return the boolean
     */
    public static boolean isPrimitives(final Class<?> clazz) {
        return isPrimitives(clazz, PRIMITIVE);
    }

    /**
     * 是否默认类型
     *
     * @param clazz     类型
     * @param predicate 断言
     * @return the boolean
     */
    public static boolean isPrimitives(final Class<?> clazz, final Predicate<Class> predicate) {
        return clazz == null ? false : (clazz.isPrimitive() ? true : (predicate == null ? false : predicate.test(clazz)));
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param clazzName  类名
     * @param methodName 方法名
     * @param argsType   参数列表
     * @return Method对象
     * @throws ClassNotFoundException 如果指定的类加载器无法定位该类
     * @throws NoSuchMethodException  如果找不到匹配的方法
     */
    public static Method getPublicMethod(final String clazzName, final String methodName, final String[] argsType) throws ClassNotFoundException, NoSuchMethodException {
        if (clazzName == null) {
            return null;
        }
        return getPublicMethod(forName(clazzName), methodName, argsType);
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param className  类名
     * @param methodName 方法名
     * @param sign       签名
     * @return Method对象
     * @throws MethodOverloadException 方法重载异常
     * @throws ClassNotFoundException  如果指定的类加载器无法定位该类
     * @throws NoSuchMethodException   如果找不到匹配的方法
     */
    public static Method getPublicMethod(final String className, final String methodName, final int sign) throws ClassNotFoundException, NoSuchMethodException {
        return className == null || methodName == null ? null : getPublicMethod(forName(className), methodName, sign);
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param clazz      类
     * @param methodName 方法名
     * @param sign       签名
     * @return Method对象
     * @throws NoSuchMethodException   如果找不到匹配的方法
     * @throws MethodOverloadException 方法重载异常
     */
    public static Method getPublicMethod(final Class clazz, final String methodName, final int sign) throws NoSuchMethodException {
        return clazz == null || methodName == null ? null : getClassMeta(clazz).getMethod(methodName, sign);
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param clazz      类
     * @param methodName 方法名
     * @param argsType   参数列表
     * @return Method对象
     * @throws NoSuchMethodException 如果找不到匹配的方法
     */
    public static Method getPublicMethod(final Class clazz, final String methodName, final String[] argsType) throws NoSuchMethodException {
        return clazz == null || methodName == null ? null : getClassMeta(clazz).getMethod(methodName, signMethod(methodName, argsType));
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param className  类
     * @param methodName 方法名
     * @return Method对象
     * @throws ClassNotFoundException  类找不到
     * @throws NoSuchMethodException   如果找不到匹配的方法
     * @throws MethodOverloadException 有方法重载异常
     */
    public static Method getPublicMethod(final String className, final String methodName) throws ClassNotFoundException, NoSuchMethodException, MethodOverloadException {
        if (className == null) {
            return null;
        }
        return getPublicMethod(forName(className), methodName);
    }

    /**
     * 获取唯一的指定名称的方法，过滤掉了Object类型的方法
     *
     * @param clazz      类
     * @param methodName 方法名
     * @return Method对象
     * @throws NoSuchMethodException   如果找不到匹配的方法
     * @throws MethodOverloadException 有方法重载异常
     */
    public static Method getPublicMethod(final Class clazz, final String methodName)
            throws NoSuchMethodException, MethodOverloadException {
        return clazz == null || methodName == null ? null : getClassMeta(clazz).getMethod(methodName);
    }

    /**
     * 获取包装参数的动态类
     *
     * @param clazz      类
     * @param methodName 方法名
     * @param function   函数
     * @return 包装参数的动态类
     * @throws NoSuchMethodException   如果找不到匹配的方法
     * @throws MethodOverloadException 有方法重载异常
     */
    public static GrpcType getGrpcType(final Class clazz, final String methodName,
                                       final BiFunction<Class, Method, GrpcType> function)
            throws NoSuchMethodException, MethodOverloadException {
        return clazz == null || methodName == null ? null : getClassMeta(clazz).getMethodMeta().
                getMethodInfo(methodName).getGrpcType(function);
    }

    /**
     * 获取重载的方法列表
     *
     * @param clazz      类
     * @param methodName 方法名
     * @return Method对象
     * @throws NoSuchMethodException 如果找不到匹配的方法
     */
    public static Collection<Method> getPublicMethods(final Class clazz, final String methodName) throws NoSuchMethodException {
        return clazz == null || methodName == null ? null : getClassMeta(clazz).getMethods(methodName);
    }

    /**
     * 获取公共的Method方法，过滤掉了Object类型的方法
     *
     * @param clazz 类
     */
    public static List<Method> getPublicMethod(final Class clazz) {
        return clazz == null ? null : getClassMeta(clazz).getMethods();
    }

    /**
     * 方法签名
     *
     * @param methodName 方法名称
     * @param types      参数
     * @return
     */
    public static int signMethod(final String methodName, final String[] types) {
        return Arrays.hashCode(types);
    }

    /**
     * 方法签名
     *
     * @param methodName 方法名称
     * @param types      参数
     * @return
     */
    public static int signMethod(final String methodName, final Class<?>[] types) {
        if (types == null) {
            return 0;
        }
        int sign = 1;
        for (Class<?> type : types) {
            sign = 31 * sign + (type == null ? 0 : type.getName().hashCode());
        }
        return sign;
    }

    /**
     * 方法签名
     *
     * @param method 方法
     * @return
     */
    public static int signMethod(final Method method) {
        if (method == null) {
            return 0;
        }
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return 0;
        }
        int sign = 1;
        for (Parameter parameter : parameters) {
            sign = 31 * sign + parameter.getType().getName().hashCode();
        }
        return sign;
    }

    /**
     * 是否是读方法
     *
     * @param method
     * @return
     */
    public static boolean isReader(final Method method) {
        if (method == null) {
            return false;
        }

        int mod = method.getModifiers();
        String name = method.getName();
        if (!Modifier.isPublic(mod)
                || Modifier.isStatic(mod)
                || method.getReturnType() == void.class
                || method.getDeclaringClass() == Object.class
                || method.getParameterTypes().length != 0) {
            return false;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return true;
        } else if (name.startsWith("is") && name.length() > 2) {
            return true;
        }
        return false;
    }

    /**
     * 是否是写方法
     *
     * @param method
     * @return
     */
    public static boolean isWritter(final Method method) {
        return method != null
                && Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && method.getDeclaringClass() != Object.class
                && method.getParameterTypes().length == 1
                && method.getName().startsWith("set")
                && method.getName().length() > 3;
    }

    /**
     * 获取属性名称
     *
     * @param method
     * @return
     */
    public static String getReaderProperty(final Method method) {
        if (isReader(method)) {
            String name = method.getName();
            if (name.startsWith("get")) {
                return name.substring(3, 4).toLowerCase()
                        + name.substring(4);
            }
            if (name.startsWith("is")) {
                return name.substring(2, 3).toLowerCase()
                        + name.substring(3);
            }
        }
        return null;
    }


    /**
     * 得到当前ClassLoader
     *
     * @return ClassLoader
     */
    public static ClassLoader getCurrentClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassUtils.class.getClassLoader();
        }
        return cl == null ? ClassLoader.getSystemClassLoader() : cl;
    }

    /**
     * 得到当前ClassLoader
     *
     * @param clazz 某个类
     * @return ClassLoader
     */
    public static ClassLoader getClassLoader(final Class<?> clazz) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            return loader;
        }
        if (clazz != null) {
            loader = clazz.getClassLoader();
            if (loader != null) {
                return loader;
            }
            return clazz.getClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }

    /**
     * 根据类名加载Class
     *
     * @param className 类名
     * @return Class
     * @throws ClassNotFoundException 找不到类
     */
    public static Class forName(final String className) throws ClassNotFoundException {
        return forName(className, true, getCurrentClassLoader());
    }

    /**
     * 根据类名加载Class
     *
     * @param className  类名
     * @param initialize 是否初始化
     * @return Class
     * @throws ClassNotFoundException 找不到类
     */
    public static Class forName(final String className, final boolean initialize) throws ClassNotFoundException {
        return forName(className, initialize, getCurrentClassLoader());
    }

    /**
     * 根据类名加载Class
     *
     * @param className   类名
     * @param classLoader Classloader
     * @return Class
     * @throws ClassNotFoundException 找不到类
     */
    public static Class forName(final String className, final ClassLoader classLoader) throws ClassNotFoundException {
        return forName(className, true, classLoader);
    }

    /**
     * 根据类名加载Class
     *
     * @param className   类名
     * @param initialize  是否初始化
     * @param classLoader Classloader
     * @return Class
     * @throws ClassNotFoundException 找不到类
     */
    public static Class forName(final String className, final boolean initialize, final ClassLoader classLoader) throws ClassNotFoundException {
        Class result = forNameQuiet(className, initialize, classLoader);
        if (result == null) {
            throw new ClassNotFoundException(className);
        }
        return result;
    }

    /**
     * 根据类名加载Class，不存在返回空
     *
     * @param className 类名
     * @return Class
     */
    public static Class forNameQuiet(final String className) {
        return forNameQuiet(className, true, getCurrentClassLoader());
    }

    /**
     * 根据类名加载Class，不存在返回空
     *
     * @param className   类名
     * @param initialize  是否初始化
     * @param classLoader Classloader
     * @return Class
     */
    public static Class forNameQuiet(final String className, final boolean initialize, final ClassLoader classLoader) {
        if (className == null) {
            return null;
        }
        Class result = forNames.get(className);
        if (result == null) {
            //不存在的类不要缓存，否则会造成漏洞，大量的无效类把内存撑爆
            try {
                result = Class.forName(className, initialize, classLoader);
                forNames.putIfAbsent(className, result);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return result;
    }

    /**
     * 获取类元数据
     *
     * @param clazz
     * @return
     */
    protected static ClassMeta getClassMeta(final Class<?> clazz) {
        return clazz == null ? null : classMetas.computeIfAbsent(clazz, k -> new ClassMeta(k));
    }

    /**
     * 获取类的泛型信息
     *
     * @param clazz
     * @return
     */
    public static GenericClass getGenericClass(final Class<?> clazz) {
        return clazz == null ? null : getClassMeta(clazz).getGenericClass();
    }

    /**
     * 获取类所在文件
     *
     * @param clazz
     * @return
     */
    public static String getCodeBase(final Class<?> clazz) {
        return clazz == null ? null : getClassMeta(clazz).getCodeBase();
    }

    /**
     * 获取类的字段
     *
     * @param clazz 类
     * @return 字段
     */
    public static List<Field> getFields(final Class<?> clazz) {
        return clazz == null ? null : getClassMeta(clazz).getFields();
    }

    /**
     * 获取类的字段
     *
     * @param clazz      类
     * @param predicate
     * @param accessible
     * @return 字段
     */
    public static Field[] getFields(final Class<?> clazz, final Predicate<Field> predicate, final boolean accessible) {
        if (clazz == null) {
            return null;
        }
        //获取字段
        List<Field> fields = getClassMeta(clazz).getFields();
        if (predicate == null) {
            //没有断言
            if (accessible) {
                fields.forEach(o -> {
                    if (!o.isAccessible()) {
                        o.setAccessible(true);
                    }
                });
            }
            return fields.toArray(new Field[fields.size()]);
        } else {
            //有断言
            LinkedList<Field> results = new LinkedList<>();
            for (Field field : fields) {
                if (predicate.test(field)) {
                    if (accessible && !field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    results.add(field);
                }
            }
            return results.toArray(new Field[results.size()]);
        }

    }

    /**
     * 判断是否是公共的实例字段，排除Static、Final和Synthetic
     *
     * @param field
     * @return
     */
    public static boolean isPublicInstanceField(final Field field) {
        if (field == null) {
            return false;
        }
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !field.isSynthetic();
    }

    /**
     * 获取类的字段名映射
     *
     * @param clazz 类
     * @return 字段
     */
    public static Map<String, Field> getFieldNames(final Class<?> clazz) {
        return clazz == null ? null : getClassMeta(clazz).getFieldNames();
    }

    /**
     * 获取类的字段
     *
     * @param clazz 类
     * @param name  属性名
     * @return 字段
     */
    public static Field getField(final Class<?> clazz, final String name) {
        return clazz == null || name == null ? null : getClassMeta(clazz).getField(name);
    }

    /**
     * 获取字段访问器
     *
     * @param clazz
     * @param field
     * @return
     */
    protected static ReflectAccessor getFieldAccessor(final Class clazz, final Field field) {
        if (clazz == null || field == null) {
            return null;
        }
        return getClassMeta(clazz).getFieldAccessor(field);
    }

    /**
     * 获取值
     *
     * @param clazz
     * @param name
     * @param target 目标对象
     * @return 属性值
     * @throws ReflectionException
     */
    public static Object getValue(final Class clazz, final String name, final Object target) throws ReflectionException {
        return getValue(clazz, getField(clazz, name), target);
    }

    /**
     * 获取值
     *
     * @param clazz
     * @param field
     * @param target 目标对象
     * @return 属性值
     * @throws ReflectionException
     */
    public static Object getValue(final Class clazz, final Field field, final Object target) throws ReflectionException {
        ReflectAccessor accessor = getFieldAccessor(clazz, field);
        return accessor == null ? null : accessor.get(target);
    }

    /**
     * 设置值
     *
     * @param clazz  类
     * @param name   字段
     * @param target 目标对象
     * @param value  属性值
     * @throws ReflectionException
     */
    public static void setValue(final Class clazz, final String name, final Object target, final Object value) throws ReflectionException {
        setValue(clazz, getField(clazz, name), target, value);
    }

    /**
     * 设置值
     *
     * @param clazz  类
     * @param field  字段
     * @param target 目标对象
     * @param value  属性值
     * @throws ReflectionException
     */
    public static void setValue(final Class clazz, final Field field, final Object target, final Object value) throws ReflectionException {
        if (target != null) {
            ReflectAccessor accessor = getFieldAccessor(clazz, field);
            if (accessor != null) {
                accessor.set(target, value);
            }
        }
    }

    /**
     * 获取构造函数
     *
     * @param type
     * @return
     */
    public static List<Constructor> getConstructors(final Class<?> type) {
        return type == null ? null : getClassMeta(type).getConstructors();
    }

    /**
     * 获取默认构造函数
     *
     * @param type
     * @return
     */
    public static Constructor getDefaultConstructor(final Class<?> type) {
        return type == null ? null : getClassMeta(type).getDefaultConstructor();
    }

    /**
     * 获取单个参数的构造函数
     *
     * @param type          目标类型
     * @param parameterType 参数类型
     * @return 字段
     */
    public static Constructor getConstructor(final Class<?> type, final Class<?> parameterType) {
        return type == null ? null : getClassMeta(type).getConstructor(parameterType);
    }

    /**
     * 装箱
     *
     * @param clazz
     * @return
     */
    public static Class<?> inbox(final Class<?> clazz) {
        if (clazz == null) {
            return null;
        } else if (!clazz.isPrimitive()) {
            return clazz;
        } else if (int.class.equals(clazz)) {
            return Integer.class;
        } else if (double.class.equals(clazz)) {
            return Double.class;
        } else if (char.class.equals(clazz)) {
            return Character.class;
        } else if (boolean.class.equals(clazz)) {
            return Boolean.class;
        } else if (long.class.equals(clazz)) {
            return Long.class;
        } else if (float.class.equals(clazz)) {
            return Float.class;
        } else if (short.class.equals(clazz)) {
            return Short.class;
        } else if (byte.class.equals(clazz)) {
            return Byte.class;
        } else {
            return clazz;
        }
    }

    /**
     * 迭代父类
     *
     * @param clazz
     * @return
     */
    public static Iterator<Class> iterate(final Class<?> clazz) {
        return new SuperIterator(clazz);
    }

    /**
     * 迭代父类
     *
     * @param clazz
     * @param predicate
     * @return
     */
    public static Iterator<Class> iterate(final Class<?> clazz, final Predicate<Class> predicate) {
        return new SuperIterator(clazz, predicate);
    }

    /**
     * 实例化一个对象(只检测默认构造函数，其它不管）
     *
     * @param clazz 对象类
     * @param <T>   对象具体类
     * @return 对象实例
     * @throws CreationException 实例化异常
     */
    public static <T> T newInstance(final Class<T> clazz) throws CreationException {
        return clazz == null ? null : getClassMeta(clazz).newInstance();
    }

    /**
     * Class[]转String[]
     *
     * @param names 对象描述[]
     * @return Class[]
     * @throws ClassNotFoundException
     */
    public static Class[] getClasses(final String[] names) throws ClassNotFoundException {
        if (names == null || names.length == 0) {
            return new Class[0];
        } else {
            Class[] classes = new Class[names.length];
            for (int i = 0; i < names.length; i++) {
                classes[i] = getClass(names[i]);
            }
            return classes;
        }
    }

    /**
     * 类名称数组转换成类数组，如果不存在，抛出运行时异常
     *
     * @param names    类名称数组
     * @param function 异常转换
     * @return Class[]
     * @throws RuntimeException
     */
    public static Class[] getClasses(final String[] names, final Function<ClassNotFoundException, RuntimeException> function) throws RuntimeException {
        if (names == null || names.length == 0) {
            return new Class[0];
        } else {
            Class[] classes = new Class[names.length];
            for (int i = 0; i < names.length; i++) {
                classes[i] = getClass(names[i], function);
            }
            return classes;
        }
    }

    /**
     * String转Class，如果不存在，抛出运行时异常
     *
     * @param name     对象描述
     * @param function
     * @return Class
     * @throws RuntimeException
     */
    public static Class getClass(final String name, final Function<ClassNotFoundException, RuntimeException> function) throws RuntimeException {
        try {
            return getClass(name);
        } catch (ClassNotFoundException e) {
            throw function != null ? function.apply(e) : new RuntimeException(e);
        }
    }

    /**
     * String转Class
     *
     * @param name 对象描述
     * @return Class
     * @throws ClassNotFoundException
     */
    public static Class getClass(final String name) throws ClassNotFoundException {
        if (name == null) {
            return null;
        }
        Class result = nameTypes.get(name);
        if (result == null) {
            switch (name) {
                case "void":
                    result = void.class;
                    break;
                case "boolean":
                    result = boolean.class;
                    break;
                case "byte":
                    result = byte.class;
                    break;
                case "char":
                    result = char.class;
                    break;
                case "double":
                    result = double.class;
                    break;
                case "float":
                    result = float.class;
                    break;
                case "int":
                    result = int.class;
                    break;
                case "long":
                    result = long.class;
                    break;
                case "short":
                    result = short.class;
                    break;
                default:
                    //不存在的不要缓存，防止缓存大量的无效类，撑爆内存
                    result = forName(canonicalNameToJvmName(name));
            }
            nameTypes.putIfAbsent(name, result);
        }
        return result;
    }

    /**
     * 判断返回值是否是CompletableFuture
     *
     * @param clazz
     * @return
     */
    public static boolean isReturnFuture(final Class<?> clazz, final Method method) {
        //TODO 进行泛型判断
        return CompletableFuture.class.isAssignableFrom(method.getReturnType());
    }

    protected static String canonicalNameToJvmName(String name) {
        boolean isarray = name.endsWith("[]");
        if (isarray) {
            String t = ""; // 计数，看上几维数组
            while (isarray) {
                name = name.substring(0, name.length() - 2);
                t += "[";
                isarray = name.endsWith("[]");
            }
            switch (name) {
                case "void":
                    name = t + "V";
                    break;
                case "boolean":
                    name = t + "Z";
                    break;
                case "byte":
                    name = t + "B";
                    break;
                case "char":
                    name = t + "C";
                    break;
                case "double":
                    name = t + "D";
                    break;
                case "float":
                    name = t + "F";
                    break;
                case "int":
                    name = t + "I";
                    break;
                case "long":
                    name = t + "J";
                    break;
                case "short":
                    name = t + "S";
                    break;
                default:
                    name = t + "L" + name + ";";
                    break;
            }
        }
        return name;
    }

    /**
     * Class[]转String[] <br>
     * 注意，得到的String可能不能直接用于Class.forName，请使用getClass(String)反向获取
     *
     * @param types Class[]
     * @return 对象描述
     */
    public static String[] getNames(final Class[] types) {
        if (types == null || types.length == 0) {
            return new String[0];
        } else {
            String[] strings = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                strings[i] = getName(types[i]);
            }
            return strings;
        }
    }

    /**
     * Class转String<br>
     * 注意，得到的String可能不能直接用于Class.forName，请使用getClass(String)反向获取
     *
     * @param clazz Class
     * @return 对象
     * @see #getClass(String)
     */
    public static String getName(final Class clazz) {
        return clazz == null ? null : typeNames.computeIfAbsent(clazz,
                o -> o.isArray() ? jvmNameToCanonicalName(clazz.getName()) : clazz.getName());
    }

    /**
     * 创建集合对象
     *
     * @param targetType
     * @param size
     * @return
     * @throws Exception
     */
    public static Collection createCollection(final Class<?> targetType, final int size) throws Exception {
        if (targetType == null) {
            return null;
        } else if (targetType.equals(List.class)) {
            return new ArrayList(size);
        } else if (targetType.equals(Set.class)) {
            return new HashSet(size);
        } else if (targetType.equals(SortedSet.class)) {
            return new TreeSet();
        } else if (targetType.isInterface()) {
            // 接口
            return null;
        } else if (Modifier.isAbstract(targetType.getModifiers())) {
            //抽象方法
            return null;
        } else {
            return (Collection) targetType.newInstance();
        }
    }

    protected static String jvmNameToCanonicalName(final String jvmName) {
        boolean isarray = jvmName.charAt(0) == '[';
        if (isarray) {
            String cnName = ""; // 计数，看上几维数组
            int i = 0;
            for (; i < jvmName.length(); i++) {
                if (jvmName.charAt(i) != '[') {
                    break;
                }
                cnName += "[]";
            }
            String componentType = jvmName.substring(i, jvmName.length());
            if ("Z".equals(componentType)) {
                cnName = "boolean" + cnName;
            } else if ("B".equals(componentType)) {
                cnName = "byte" + cnName;
            } else if ("C".equals(componentType)) {
                cnName = "char" + cnName;
            } else if ("D".equals(componentType)) {
                cnName = "double" + cnName;
            } else if ("F".equals(componentType)) {
                cnName = "float" + cnName;
            } else if ("I".equals(componentType)) {
                cnName = "int" + cnName;
            } else if ("J".equals(componentType)) {
                cnName = "long" + cnName;
            } else if ("S".equals(componentType)) {
                cnName = "short" + cnName;
            } else {
                cnName = componentType.substring(1, componentType.length() - 1) + cnName; // 对象的 去掉L
            }
            return cnName;
        }
        return jvmName;
    }

    /**
     * 类元数据
     */
    protected static class ClassMeta {
        /**
         * 类型
         */
        protected Class type;
        /**
         * 字段元数据
         */
        protected volatile FieldMeta fieldMeta;
        /**
         * 构造函数元数据
         */
        protected volatile ConstructorMeta constructorMeta;
        /**
         * 方法元数据
         */
        protected volatile MethodMeta methodMeta;
        /**
         * 字段访问器
         */
        protected volatile Map<Field, Optional<ReflectAccessor>> accessors;
        /**
         * 泛型信息
         */
        protected volatile GenericClass genericClass;
        /**
         * jar文件
         */
        protected volatile Optional<String> codebase;

        /**
         * 构造函数
         *
         * @param type
         */
        public ClassMeta(final Class type) {
            this.type = type;
        }

        /**
         * 获取构造函数元数据，延迟加载
         *
         * @return
         */
        protected ConstructorMeta getConstructorMeta() {
            if (constructorMeta == null) {
                synchronized (this) {
                    constructorMeta = new ConstructorMeta(type);
                }
            }
            return constructorMeta;
        }

        /**
         * 获取字段元数据
         */
        protected FieldMeta getFieldMeta() {
            if (fieldMeta == null) {
                synchronized (this) {
                    if (fieldMeta == null) {
                        fieldMeta = new FieldMeta(type);
                    }
                }
            }
            return fieldMeta;
        }

        /**
         * 获取方法元数据
         *
         * @return
         */
        protected MethodMeta getMethodMeta() {
            if (methodMeta == null) {
                synchronized (this) {
                    if (methodMeta == null) {
                        methodMeta = new MethodMeta(type);
                    }
                }
            }
            return methodMeta;
        }

        /**
         * 获取泛型信息
         */
        public GenericClass getGenericClass() {
            if (genericClass == null) {
                synchronized (this) {
                    if (genericClass == null) {
                        genericClass = new GenericClass(type);
                    }
                }
            }
            return genericClass;
        }

        /**
         * 获取字段
         *
         * @return
         */
        public List<Field> getFields() {
            return getFieldMeta().getFields();
        }

        /**
         * 获取字段名称
         *
         * @return
         */
        public Map<String, Field> getFieldNames() {
            return getFieldMeta().getFieldNames();
        }

        /**
         * 根据名称获取字段
         *
         * @param name
         * @return
         */
        public Field getField(final String name) {
            return getFieldMeta().getField(name);
        }

        /**
         * 获取字段访问器
         *
         * @param field
         * @return
         */
        protected ReflectAccessor getFieldAccessor(final Field field) {
            if (accessors == null) {
                synchronized (this) {
                    if (accessors == null) {
                        accessors = new ConcurrentHashMap<>(fieldMeta != null ? fieldMeta.fields.size() : 20);
                    }
                }
            }
            return field == null ? null : accessors.computeIfAbsent(field, k -> {
                Class<?> fieldType = field.getType();
                String name = k.getName();
                char[] data = name.toCharArray();
                data[0] = Character.toUpperCase(data[0]);
                name = new String(data);
                String getName = "get" + name;
                String getBoolName = (fieldType == boolean.class || fieldType == Boolean.class) ? "is" + name : null;
                String setName = "set" + name;
                MethodMeta meta = getMethodMeta();
                // 获取GETTER方法
                // 方法的名称会放入JVM的常量池里面
                OverloadMethod getterMethod = meta.getOverloadMethod(getName);
                OverloadMethod boolMethod = getBoolName == null ? null : meta.getOverloadMethod(getBoolName);
                OverloadMethod setterMethod = meta.getOverloadMethod(setName);

                Method getter = getterMethod == null ? (boolMethod == null ? null : boolMethod.getMethod(fieldType)) : getterMethod.getMethod(fieldType);
                Method setter = setterMethod == null ? null : setterMethod.getMethod(fieldType);

                return Optional.of(new ReflectAccessor(field, getter, setter));

            }).get();
        }

        /**
         * 获取重载方法
         *
         * @param name
         * @return
         */
        public OverloadMethod getOverloadMethod(final String name) {
            return getMethodMeta().getOverloadMethod(name);
        }

        /**
         * 根据签名获取方法
         *
         * @param name
         * @param sign
         * @return
         * @throws NoSuchMethodException
         */
        public Method getMethod(final String name, final int sign) throws NoSuchMethodException {
            return getMethodMeta().getMethod(name, sign);
        }

        /**
         * 获取单一方法
         *
         * @param name
         * @return
         * @throws NoSuchMethodException
         * @throws MethodOverloadException
         */
        public Method getMethod(final String name) throws NoSuchMethodException, MethodOverloadException {
            return getMethodMeta().getMethod(name);
        }

        /**
         * 获取重载的方法列表
         *
         * @param name
         * @return
         * @throws NoSuchMethodException
         */
        public Collection<Method> getMethods(final String name) throws NoSuchMethodException {
            return getMethodMeta().getMethods(name);
        }

        /**
         * 获取公共方法列表
         *
         * @return
         */
        public List<Method> getMethods() {
            return getMethodMeta().getMethods();
        }

        /**
         * 获取单一参数的构造函数
         *
         * @param type
         * @return
         */
        public Constructor getConstructor(final Class type) {
            return type == null ? null : getConstructorMeta().getConstructor(type);
        }

        /**
         * 获取默认构造函数
         *
         * @return
         */
        public Constructor getDefaultConstructor() {
            return getConstructorMeta().getDefaultConstructor();
        }

        /**
         * 获取所有构造函数
         *
         * @return
         */
        public List<Constructor> getConstructors() {
            return getConstructorMeta().getConstructors();
        }

        /**
         * 得到类所在地址，可以是文件，也可以是jar包
         *
         * @return the code base
         */
        public String getCodeBase() {
            if (codebase == null) {
                synchronized (this) {
                    if (codebase == null) {
                        String file = null;
                        ProtectionDomain domain = type.getProtectionDomain();
                        if (domain != null) {
                            CodeSource source = domain.getCodeSource();
                            if (source != null) {
                                URL location = source.getLocation();
                                if (location != null) {
                                    file = location.getFile();
                                }
                            }
                        }
                        codebase = Optional.ofNullable(file);
                    }
                }
            }
            return codebase.get();

        }

        /**
         * 实例化
         *
         * @param <T>
         * @return
         * @throws CreationException
         */
        public <T> T newInstance() throws CreationException {
            return getConstructorMeta().newInstance();
        }
    }

    /**
     * 字段元数据
     */
    protected static class FieldMeta {
        /**
         * 类型
         */
        protected Class type;
        /**
         * 字段
         */
        protected List<Field> fields = new LinkedList<>();
        /**
         * 字段名称
         */
        protected Map<String, Field> fieldNames;

        public FieldMeta(Class type) {
            this.type = type;
            //判断非基本类型，非数组，非接口
            if (!type.isPrimitive() && !type.isArray() && !type.isInterface()) {
                //迭代父类获取字段
                Iterator<Class> iterator = iterate(type);
                while (iterator.hasNext()) {
                    for (Field field : iterator.next().getDeclaredFields()) {
                        fields.add(field);
                    }
                }
                fieldNames = new HashMap<>(fields.size());
                for (Field field : fields) {
                    fieldNames.put(field.getName(), field);
                }
            } else {
                fieldNames = new HashMap<>();
            }
        }

        public List<Field> getFields() {
            return fields;
        }

        public Map<String, Field> getFieldNames() {
            return fieldNames;
        }

        /**
         * 获取字段
         *
         * @param name
         * @return
         */
        public Field getField(final String name) {
            return name == null ? null : fieldNames.get(name);
        }
    }

    /**
     * 方法元数据
     */
    protected static class MethodMeta {
        /**
         * 类型
         */
        protected Class type;
        /**
         * 方法重载信息
         */
        protected Map<String, OverloadMethod> overloadMethods;
        /**
         * 公共方法
         */
        protected List<Method> methods;

        /**
         * 构造函数
         *
         * @param type
         */
        public MethodMeta(Class type) {
            this.type = type;
            if (!type.isPrimitive() && !type.isArray()) {
                Method[] publicMethods = type.getMethods();
                overloadMethods = new HashMap<>(publicMethods.length);
                methods = new ArrayList<>(publicMethods.length);
                for (Method method : publicMethods) {
                    if (!method.getDeclaringClass().equals(Object.class)) {
                        overloadMethods.computeIfAbsent(method.getName(), k -> new OverloadMethod(type, k)).add(method);
                        methods.add(method);
                    }
                }
            } else {
                overloadMethods = new HashMap<>(0);
                methods = new ArrayList<>(0);
            }
        }

        /**
         * 获取重载方法
         *
         * @param name
         * @return
         */
        public OverloadMethod getOverloadMethod(final String name) {
            return overloadMethods.get(name);
        }

        public List<Method> getMethods() {
            return methods;
        }

        /**
         * 根据签名获取方法
         *
         * @param name
         * @param sign
         * @return
         * @throws NoSuchMethodException
         */
        public Method getMethod(final String name, final int sign) throws NoSuchMethodException {
            OverloadMethod method = getOverloadMethod(name);
            return method == null ? null : method.getMethod(sign);
        }

        /**
         * 获取单一方法信息
         *
         * @param name
         * @return
         * @throws NoSuchMethodException
         * @throws MethodOverloadException
         */
        public MethodInfo getMethodInfo(final String name) throws NoSuchMethodException, MethodOverloadException {
            OverloadMethod method = getOverloadMethod(name);
            if (method == null) {
                throw new NoSuchMethodException(String.format("Method is not found. %s", name));
            }
            return method.get();
        }

        /**
         * 获取单一方法
         *
         * @param name
         * @return
         * @throws NoSuchMethodException
         * @throws MethodOverloadException
         */
        public Method getMethod(final String name) throws NoSuchMethodException, MethodOverloadException {
            OverloadMethod method = getOverloadMethod(name);
            if (method == null) {
                throw new NoSuchMethodException(String.format("Method is not found. %s", name));
            }
            return method.getMethod();
        }

        /**
         * 获取重载的方法列表
         *
         * @param name
         * @return
         * @throws NoSuchMethodException
         */
        public Collection<Method> getMethods(final String name) throws NoSuchMethodException {
            OverloadMethod method = getOverloadMethod(name);
            if (method == null) {
                throw new NoSuchMethodException(String.format("Method is not found. %s", name));
            }
            return method.getMethods();
        }
    }

    /**
     * 重载的方法，在同步块里面添加
     */
    protected static class OverloadMethod {
        /**
         * 类型
         */
        protected Class clazz;
        /**
         * 名称
         */
        protected String name;
        /**
         * 第一个方法
         */
        protected MethodInfo first;
        /**
         * 多个方法的签名
         */
        protected Map<Integer, MethodInfo> signs;
        /**
         * 方法元数据
         */
        protected Map<Method, MethodInfo> metas;

        /**
         * 构造函数
         *
         * @param clazz
         * @param name
         */
        public OverloadMethod(Class clazz, String name) {
            this.clazz = clazz;
            this.name = name;
        }

        /**
         * 构造函数
         *
         * @param clazz
         * @param method
         */
        public OverloadMethod(Class clazz, Method method) {
            this.clazz = clazz;
            this.name = method.getName();
            this.first = new MethodInfo(clazz, method);
        }

        /**
         * 添加方法
         *
         * @param method
         */
        public void add(final Method method) {
            if (first == null) {
                first = new MethodInfo(clazz, method);
            } else {
                if (signs == null) {
                    signs = new HashMap<>(4);
                    metas = new HashMap<>(4);
                    signs.put(first.sign, first);
                    metas.put(first.method, first);
                }
                MethodInfo meta = new MethodInfo(clazz, method);
                signs.put(meta.sign, meta);
                metas.put(method, meta);
            }
        }

        /**
         * 根据方法获取元数据
         *
         * @param method
         * @return
         */
        public MethodInfo get(final Method method) {
            return metas != null ? metas.get(method) : (first.method == method ? first : null);
        }

        /**
         * 根据签名获取方法元数据
         *
         * @param sign
         * @return
         */
        public MethodInfo get(final int sign) {
            //如果只有一个方法，则不判断签名
            return metas == null ? first : signs.get(sign);
        }

        /**
         * 根据签名获取方法元数据
         *
         * @return
         * @throws MethodOverloadException
         */
        public MethodInfo get() throws MethodOverloadException {
            //如果只有一个方法，则不判断签名
            if (signs == null) {
                return first;
            }
            throw new MethodOverloadException(String.format("Method %s is overload.", name));
        }

        /**
         * 获取重载的方法列表
         *
         * @return
         */
        public Collection<Method> getMethods() {
            return metas.keySet();
        }

        /**
         * 获取单一方法
         *
         * @return
         * @throws MethodOverloadException
         */
        public Method getMethod() throws MethodOverloadException {
            if (signs == null) {
                return first.method;
            }
            throw new MethodOverloadException(String.format("Method %s is overload.", name));
        }

        /**
         * 根据签名获取方法
         *
         * @param sign
         * @return
         * @throws NoSuchMethodException
         */
        public Method getMethod(final int sign) throws NoSuchMethodException {
            MethodInfo meta = get(sign);
            if (meta == null) {
                throw new NoSuchMethodException(String.format("Method is not found. name=%s,sign=%d", name, sign));
            }
            return meta.method;
        }

        /**
         * 获取指定类型的单一参数的方法
         *
         * @param parameterType
         * @return
         */
        public Method getMethod(final Class parameterType) {
            Parameter[] parameters;
            if (signs == null || signs.isEmpty()) {
                parameters = first.method.getParameters();
                if (parameters.length == 1 && parameters[0].getType().equals(parameterType)) {
                    return first.method;
                }
            } else {
                for (MethodInfo info : metas.values()) {
                    parameters = info.method.getParameters();
                    if (parameters.length == 1 && parameters[0].getType().equals(parameterType)) {
                        return info.method;
                    }
                }
            }
            return null;
        }
    }

    /**
     * 方法元数据
     */
    protected static class MethodInfo {
        /**
         * 类型
         */
        protected Class clazz;
        /**
         * 方法
         */
        protected Method method;
        /**
         * 名称
         */
        protected String name;
        /**
         * 签名
         */
        protected int sign;
        /**
         * grpc类型
         */
        protected volatile GrpcType grpcType;

        /**
         * 构造函数
         *
         * @param clazz
         * @param method
         */
        public MethodInfo(Class clazz, Method method) {
            this.clazz = clazz;
            this.method = method;
            this.name = method.getName();
            this.sign = signMethod(method);
        }

        public Method getMethod() {
            return method;
        }

        public String getName() {
            return name;
        }

        public int getSign() {
            return sign;
        }

        /**
         * 获取方法类型
         *
         * @param function
         * @return
         */
        public GrpcType getGrpcType(final BiFunction<Class, Method, GrpcType> function) {
            if (grpcType == null) {
                if (function == null) {
                    return null;
                }
                synchronized (this) {
                    if (grpcType == null) {
                        grpcType = function.apply(clazz, method);
                    }
                }
            }
            return grpcType;
        }
    }

    /**
     * 构造函数
     */
    protected static class ConstructorMeta {
        /**
         * 类型
         */
        protected Class type;
        /**
         * 单参数公开的构造函数
         */
        protected Map<Class<?>, Constructor> singleConstructors = new HashMap<>(3);
        /**
         * 默认公开的构造函数
         */
        protected Constructor defaultConstructor;
        /**
         * 默认单一参数构造函数
         */
        protected Constructor defaultSingleConstructor;
        /**
         * 构造函数
         */
        protected List<Constructor> constructors = new LinkedList<>();

        public ConstructorMeta(Class type) {
            this.type = type;
            //判断是否是公共的具体实现类
            int modifiers = type.getModifiers();
            boolean b = !Modifier.isAbstract(modifiers) && !Modifier.isInterface(modifiers);
            Parameter[] parameters;
            for (Constructor c : type.getConstructors()) {
                constructors.add(c);
                if (b) {
                    parameters = c.getParameters();
                    switch (parameters.length) {
                        case 0:
                            defaultConstructor = setAccessible(c);
                            break;
                        case 1:
                            defaultSingleConstructor = defaultSingleConstructor == null ? c : defaultSingleConstructor;
                            singleConstructors.put(inbox(parameters[0].getType()), setAccessible(c));
                            break;
                    }

                }
            }
        }

        /**
         * 设置可以访问
         *
         * @param constructor
         */
        protected Constructor setAccessible(final Constructor constructor) {
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
            return constructor;
        }

        /**
         * 获取单一参数的构造函数
         *
         * @param type
         * @return
         */
        public Constructor getConstructor(final Class type) {
            return type == null ? null : singleConstructors.get(type);
        }

        public Constructor getDefaultConstructor() {
            return defaultConstructor;
        }

        public Constructor getDefaultSingleConstructor() {
            return defaultSingleConstructor;
        }

        public List<Constructor> getConstructors() {
            return constructors;
        }

        /**
         * 实例化
         *
         * @param <T>
         * @return
         * @throws CreationException
         */
        public <T> T newInstance() throws CreationException {
            try {
                if (type.isMemberClass() && !Modifier.isStatic(type.getModifiers())) {
                    if (defaultSingleConstructor != null) {
                        return (T) defaultSingleConstructor.newInstance(new Object[]{null});
                    }
                } else {
                    if (defaultConstructor != null) {
                        return (T) defaultConstructor.newInstance();
                    }
                }
                return null;
            } catch (Exception e) {
                throw new CreationException(String.format("Error occurs while instance class %s", type), e);
            }
        }
    }

    /**
     * 反射字段访问器
     */
    protected static class ReflectAccessor {
        // 字段
        protected Field field;
        // 获取方法
        protected Method getter;
        // 设置方法
        protected Method setter;

        public ReflectAccessor(Field field, Method getter, Method setter) {
            this.field = field;
            this.getter = getter;
            this.setter = setter;
        }

        /**
         * 获取值
         *
         * @param target
         * @return
         * @throws ReflectionException
         */
        public Object get(final Object target) throws ReflectionException {
            try {
                if (target == null) {
                    return null;
                } else if (getter != null) {
                    return getter.invoke(target);
                } else if (field.isAccessible()) {
                    return field.get(target);
                } else {
                    field.setAccessible(true);
                    return field.get(target);
                }
            } catch (Exception e) {
                throw new ReflectionException(e.getMessage(), e);
            }

        }

        /**
         * 设置值
         *
         * @param target
         * @param value
         * @throws ReflectionException
         */
        public void set(final Object target, final Object value) throws ReflectionException {
            try {
                if (target == null) {
                    return;
                } else if (setter != null) {
                    setter.invoke(target, value);
                } else if (field.isAccessible()) {
                    field.set(target, value);
                } else {
                    field.setAccessible(true);
                    field.set(target, value);
                }
            } catch (Exception e) {
                throw new ReflectionException(e.getMessage(), e);
            }
        }

    }

}
