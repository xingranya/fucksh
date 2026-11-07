package com.fucksh.xposed;

import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 基于 DEX 字符串特征的混淆方法定位器。
 *
 * <p>SystemHelper 每次 OTA 都可能重新混淆类名（例如 {@code e1.b#b}、{@code g1.n#a}）。
 * 与其把混淆名写死，不如在目标 APK 的 dex 中搜索方法体引用到的特征字符串，再结合
 * 返回类型/参数签名锁定方法：</p>
 *
 * <ul>
 *   <li>设备信息采集：返回 {@code String}，方法体同时引用 {@code IMEI1/ICCID1/IMSI1}；</li>
 *   <li>基站与经纬度：返回 {@code JSONObject}，方法体同时引用 {@code LONGITUDE/LATITUDE/CSIRSRP}。</li>
 * </ul>
 *
 * <p>扫描只在目标进程启动时进行一次并缓存在内存中；不落盘、不联网。定位失败时由调用方
 * 回退到当前版本的已知混淆名。</p>
 */
final class FeatureLocator {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_MATCHES_PER_FEATURE = 8;

    private static volatile String cachedKey;
    private static volatile Map<String, List<MethodRef>> cached;

    private FeatureLocator() {
    }

    /** 特征定义。字符串全部命中、签名匹配才算命中。 */
    static final class Feature {
        final String id;
        final Set<String> strings;
        final String name;
        final String returnType;
        final List<String> params;

        Feature(String id, Set<String> strings, String name, String returnType,
                List<String> params) {
            this.id = id;
            this.strings = Collections.unmodifiableSet(new LinkedHashSet<>(strings));
            this.name = name;
            this.returnType = returnType;
            this.params = params == null ? null : Collections.unmodifiableList(new ArrayList<>(params));
        }
    }

    /** 一个可反射解析的方法引用。 */
    static final class MethodRef {
        final String owner;
        final String name;
        final List<String> params;
        final String returnType;

        MethodRef(String owner, String name, List<String> params, String returnType) {
            this.owner = owner;
            this.name = name;
            this.params = Collections.unmodifiableList(new ArrayList<>(params));
            this.returnType = returnType;
        }

        String describe() {
            StringBuilder builder = new StringBuilder(className(owner));
            builder.append('#').append(name).append('(');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(simpleName(params.get(i)));
            }
            builder.append(')');
            return builder.toString();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * 扫描目标 APK，返回特征 id → 方法引用列表。
     *
     * @param info     目标应用 ApplicationInfo（提供 sourceDir/splitSourceDirs）
     * @param features 特征列表
     */
    static Map<String, List<MethodRef>> locate(ApplicationInfo info, List<Feature> features) {
        String key = cacheKey(info, features);
        Map<String, List<MethodRef>> hit = cached;
        if (hit != null && key.equals(cachedKey)) return hit;

        Map<String, List<MethodRef>> result = emptyResult(features);
        if (info == null) return result;
        List<File> apks = apkFiles(info);
        Set<String> wanted = new HashSet<>();
        for (Feature feature : features) wanted.addAll(feature.strings);

        for (File apk : apks) {
            if (apk == null || !apk.isFile()) continue;
            ZipFile zip = null;
            try {
                zip = new ZipFile(apk);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!isDexEntry(name)) continue;
                    InputStream input = null;
                    try {
                        input = zip.getInputStream(entry);
                        byte[] dex = readAll(input);
                        scanDex(dex, features, wanted, result);
                    } catch (Throwable error) {
                        // 单个 dex 失败不影响其它 dex。
                    } finally {
                        closeQuietly(input);
                    }
                }
            } catch (Throwable error) {
                // APK 不可读时直接返回已收集结果，由调用方回退。
            } finally {
                closeQuietly(zip);
            }
        }

        cachedKey = key;
        cached = result;
        return result;
    }

    /** 把方法引用解析成可 Hook 的反射 Method。 */
    static Method resolve(MethodRef ref, ClassLoader classLoader) {
        if (ref == null || classLoader == null) return null;
        try {
            Class<?> owner = Class.forName(className(ref.owner), false, classLoader);
            Class<?>[] params = new Class<?>[ref.params.size()];
            for (int i = 0; i < params.length; i++) {
                params[i] = resolveType(ref.params.get(i), classLoader);
                if (params[i] == null) return null;
            }
            Method method = owner.getDeclaredMethod(ref.name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 已知混淆名的回退解析。 */
    static Method resolveKnown(ClassLoader classLoader, String owner, String name,
                               Class<?>... params) {
        if (classLoader == null) return null;
        try {
            Class<?> type = Class.forName(owner, false, classLoader);
            Method method = type.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            return null;
        }
    }

    private static Map<String, List<MethodRef>> emptyResult(List<Feature> features) {
        Map<String, List<MethodRef>> result = new LinkedHashMap<>();
        for (Feature feature : features) result.put(feature.id, new ArrayList<>());
        return result;
    }

    private static String cacheKey(ApplicationInfo info, List<Feature> features) {
        StringBuilder builder = new StringBuilder();
        if (info != null && info.sourceDir != null) {
            File source = new File(info.sourceDir);
            builder.append(info.sourceDir).append(':').append(source.lastModified()).append(':').append(source.length());
            if (info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) builder.append('|').append(split);
            }
        }
        for (Feature feature : features) {
            builder.append(';').append(feature.id).append(feature.strings)
                    .append(feature.name).append(feature.returnType).append(feature.params);
        }
        return builder.toString();
    }

    private static List<File> apkFiles(ApplicationInfo info) {
        List<File> files = new ArrayList<>();
        if (info.sourceDir != null) files.add(new File(info.sourceDir));
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) {
                if (split != null) files.add(new File(split));
            }
        }
        return files;
    }

    private static boolean isDexEntry(String name) {
        if (name == null || !name.endsWith(".dex")) return false;
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (!base.startsWith("classes")) return false;
        String suffix = base.substring("classes".length(), base.length() - ".dex".length());
        if (suffix.isEmpty()) return true;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    private static void scanDex(byte[] bytes, List<Feature> features, Set<String> wanted,
                                Map<String, List<MethodRef>> result) {
        if (bytes == null || bytes.length < 0x70) return;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.get(0) != 'd' || buf.get(1) != 'e' || buf.get(2) != 'x' || buf.get(3) != '\n') {
            return;
        }

        int stringIdsSize = buf.getInt(56);
        int stringIdsOff = buf.getInt(60);
        int typeIdsOff = buf.getInt(68);
        int protoIdsOff = buf.getInt(76);
        int methodIdsOff = buf.getInt(92);
        int classDefsSize = buf.getInt(96);
        int classDefsOff = buf.getInt(100);
        if (stringIdsSize <= 0 || stringIdsOff <= 0 || classDefsSize <= 0 || classDefsOff <= 0) {
            return;
        }

        StringTable strings = new StringTable(buf, stringIdsSize, stringIdsOff);
        int[] typeStrings = new int[Math.max(0, (buf.getInt(64)))];
        for (int i = 0; i < typeStrings.length; i++) {
            typeStrings[i] = buf.getInt(typeIdsOff + i * 4);
        }

        for (int i = 0; i < classDefsSize; i++) {
            int base = classDefsOff + i * 32;
            if (base + 32 > buf.limit()) break;
            int classDataOff = buf.getInt(base + 24);
            if (classDataOff <= 0) continue;
            scanClassData(buf, classDataOff, strings, typeStrings, protoIdsOff, methodIdsOff,
                    features, wanted, result);
        }
    }

    private static void scanClassData(ByteBuffer buf, int classDataOff, StringTable strings,
                                      int[] typeStrings, int protoIdsOff, int methodIdsOff,
                                      List<Feature> features, Set<String> wanted,
                                      Map<String, List<MethodRef>> result) {
        int[] cursor = {classDataOff};
        int staticFields = readUleb(buf, cursor);
        int instanceFields = readUleb(buf, cursor);
        int directMethods = readUleb(buf, cursor);
        int virtualMethods = readUleb(buf, cursor);

        for (int i = 0; i < staticFields + instanceFields; i++) {
            readUleb(buf, cursor); // field_idx_diff
            readUleb(buf, cursor); // access_flags
        }

        int methodIndex = 0;
        for (int i = 0; i < directMethods; i++) {
            methodIndex += readUleb(buf, cursor);
            readUleb(buf, cursor); // access_flags
            int codeOff = readUleb(buf, cursor);
            if (codeOff > 0) {
                inspectMethod(buf, codeOff, methodIndex, strings, typeStrings, protoIdsOff,
                        methodIdsOff, features, wanted, result);
            }
        }

        methodIndex = 0;
        for (int i = 0; i < virtualMethods; i++) {
            methodIndex += readUleb(buf, cursor);
            readUleb(buf, cursor);
            int codeOff = readUleb(buf, cursor);
            if (codeOff > 0) {
                inspectMethod(buf, codeOff, methodIndex, strings, typeStrings, protoIdsOff,
                        methodIdsOff, features, wanted, result);
            }
        }
    }

    private static void inspectMethod(ByteBuffer buf, int codeOff, int methodIndex,
                                      StringTable strings, int[] typeStrings, int protoIdsOff,
                                      int methodIdsOff, List<Feature> features, Set<String> wanted,
                                      Map<String, List<MethodRef>> result) {
        if (codeOff + 16 > buf.limit()) return;
        int insnsSize = buf.getInt(codeOff + 12);
        if (insnsSize <= 0 || insnsSize > 1_000_000) return;
        int insnsOff = codeOff + 16;
        if (insnsOff + insnsSize * 2L > buf.limit()) return;

        Set<String> referenced = new HashSet<>();
        for (int i = 0; i < insnsSize; i++) {
            int unit = buf.getShort(insnsOff + i * 2) & 0xffff;
            if (unit == 0x1a && i + 1 < insnsSize) {
                int index = buf.getShort(insnsOff + (i + 1) * 2) & 0xffff;
                addReferenced(strings, index, wanted, referenced);
            } else if (unit == 0x1b && i + 2 < insnsSize) {
                int low = buf.getShort(insnsOff + (i + 1) * 2) & 0xffff;
                int high = buf.getShort(insnsOff + (i + 2) * 2) & 0xffff;
                int index = low | (high << 16);
                addReferenced(strings, index, wanted, referenced);
            }
        }
        if (referenced.isEmpty()) return;

        MethodRef ref = methodRef(buf, methodIndex, strings, typeStrings, protoIdsOff, methodIdsOff);
        if (ref == null) return;
        for (Feature feature : features) {
            if (!matchesSignature(feature, ref)) continue;
            if (!referenced.containsAll(feature.strings)) continue;
            List<MethodRef> matches = result.get(feature.id);
            if (matches == null) {
                matches = new ArrayList<>();
                result.put(feature.id, matches);
            }
            if (matches.size() >= MAX_MATCHES_PER_FEATURE) continue;
            boolean duplicate = false;
            for (MethodRef existing : matches) {
                if (existing.owner.equals(ref.owner) && existing.name.equals(ref.name)
                        && existing.params.equals(ref.params)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) matches.add(ref);
        }
    }

    private static void addReferenced(StringTable strings, int index, Set<String> wanted,
                                      Set<String> referenced) {
        if (index < 0 || index >= strings.size()) return;
        String value = strings.get(index);
        if (value != null && wanted.contains(value)) referenced.add(value);
    }

    private static boolean matchesSignature(Feature feature, MethodRef ref) {
        if (feature.name != null && !feature.name.equals(ref.name)) return false;
        if (feature.returnType != null && !feature.returnType.equals(ref.returnType)) return false;
        if (feature.params != null && !feature.params.equals(ref.params)) return false;
        return true;
    }

    private static MethodRef methodRef(ByteBuffer buf, int methodIndex, StringTable strings,
                                       int[] typeStrings, int protoIdsOff, int methodIdsOff) {
        try {
            int base = methodIdsOff + methodIndex * 8;
            if (base + 8 > buf.limit()) return null;
            int classIdx = buf.getShort(base) & 0xffff;
            int protoIdx = buf.getShort(base + 2) & 0xffff;
            int nameIdx = buf.getInt(base + 4);
            String owner = typeName(typeStrings, strings, classIdx);
            String name = strings.get(nameIdx);
            if (owner == null || name == null) return null;

            int protoBase = protoIdsOff + protoIdx * 12;
            if (protoBase + 12 > buf.limit()) return null;
            String returnType = typeName(typeStrings, strings, buf.getInt(protoBase + 4));
            int paramsOff = buf.getInt(protoBase + 8);
            List<String> params = new ArrayList<>();
            if (paramsOff > 0 && paramsOff + 4 <= buf.limit()) {
                int count = buf.getInt(paramsOff);
                if (count < 0 || count > 256) return null;
                for (int i = 0; i < count; i++) {
                    int typeIdx = buf.getShort(paramsOff + 4 + i * 2) & 0xffff;
                    String type = typeName(typeStrings, strings, typeIdx);
                    if (type == null) return null;
                    params.add(type);
                }
            }
            return new MethodRef(owner, name, params, returnType);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String typeName(int[] typeStrings, StringTable strings, int typeIndex) {
        if (typeIndex < 0 || typeIndex >= typeStrings.length) return null;
        return strings.get(typeStrings[typeIndex]);
    }

    private static int readUleb(ByteBuffer buf, int[] cursor) {
        int position = cursor[0];
        int result = 0;
        int shift = 0;
        while (position < buf.limit() && shift < 35) {
            int value = buf.get(position++) & 0xff;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
        }
        cursor[0] = position;
        return result;
    }

    private static Class<?> resolveType(String descriptor, ClassLoader classLoader) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        switch (descriptor.charAt(0)) {
            case 'V': return void.class;
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'S': return short.class;
            case 'C': return char.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'L':
            case '[':
                try {
                    return Class.forName(className(descriptor), false, classLoader);
                } catch (Throwable error) {
                    return null;
                }
            default:
                return null;
        }
    }

    static String className(String descriptor) {
        if (descriptor == null) return "";
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        if (descriptor.startsWith("[")) {
            return descriptor.replace('/', '.');
        }
        return descriptor;
    }

    private static String simpleName(String descriptor) {
        if (descriptor == null) return "";
        String name = className(descriptor);
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(4096, input.available()));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            if (closeable instanceof ZipFile) ((ZipFile) closeable).close();
            else if (closeable instanceof InputStream) ((InputStream) closeable).close();
        } catch (Throwable ignored) {
        }
    }

    /** 惰性解码 DEX string_ids。 */
    private static final class StringTable {
        private final ByteBuffer buf;
        private final int size;
        private final int offset;
        private final String[] cache;

        StringTable(ByteBuffer buf, int size, int offset) {
            this.buf = buf;
            this.size = size;
            this.offset = offset;
            this.cache = new String[size];
        }

        int size() {
            return size;
        }

        String get(int index) {
            if (index < 0 || index >= size) return null;
            String value = cache[index];
            if (value != null) return value;
            try {
                int stringOff = buf.getInt(offset + index * 4);
                if (stringOff <= 0 || stringOff >= buf.limit()) return null;
                int[] cursor = {stringOff};
                readUleb(buf, cursor);
                int position = cursor[0];
                ByteArrayOutputStream output = new ByteArrayOutputStream(32);
                while (position < buf.limit()) {
                    int b = buf.get(position++) & 0xff;
                    if (b == 0) break;
                    output.write(b);
                }
                value = new String(output.toByteArray(), UTF_8);
                cache[index] = value;
                return value;
            } catch (Throwable error) {
                return null;
            }
        }
    }
}
