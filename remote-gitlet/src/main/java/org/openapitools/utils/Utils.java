package org.openapitools.utils;


import org.openapitools.exception.GitletException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;


/** Assorted utilities.
 *
 * Give this file a good read as it provides several useful utility functions
 * to save you some time.
 *
 *  @author P. N. Hilfinger
 */
public class Utils {

    /** The length of a complete SHA-1 UID as a hexadecimal numeral. */
    static final int UID_LENGTH = 40;

    /* SHA-1 HASH VALUES. */

    /** 返回 vals 连接的SHA-1哈希值，可能
     * 是字节数组和字符串的任意混合。 */
    public static String sha1(Object... vals) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (Object val : vals) {
                if (val instanceof byte[]) {
                    md.update((byte[]) val);
                } else if (val instanceof String) {
                    md.update(((String) val).getBytes(StandardCharsets.UTF_8));
                } else {
                    throw new IllegalArgumentException("improper type to sha1");
                }
            }
            Formatter result = new Formatter();
            for (byte b : md.digest()) {
                result.format("%02x", b);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException excp) {
            throw new IllegalArgumentException("System does not support SHA-1");
        }
    }

    /** Returns the SHA-1 hash of the concatenation of the strings in
     *  VALS. */
    public static String sha1(List<Object> vals) {
        return sha1(vals.toArray(new Object[vals.size()]));
    }

    /* FILE DELETION */

    /** 如果 FILE 存在且不是目录，则删除它。 返回真值
     * 如果 FILE 被删除，否则为 false。 拒绝删除 FILE
     * 并且除非目录指定为
     * FILE 还包含一个名为 .gitlet 的目录。 */
    public static boolean restrictedDelete(File file) {
        if (!(new File(file.getParentFile(), ".gitlet")).isDirectory()) {
            throw new IllegalArgumentException("not .gitlet working directory");
        }
        if (!file.isDirectory()) {
            return file.delete();
        } else {
            return false;
        }
    }

    /** Deletes the file named FILE if it exists and is not a directory.
     *  Returns true if FILE was deleted, and false otherwise.  Refuses
     *  to delete FILE and throws IllegalArgumentException unless the
     *  directory designated by FILE also contains a directory named .gitlet. */
    public static boolean restrictedDelete(String file) {
        return restrictedDelete(new File(file));
    }

    /* READING AND WRITING FILE CONTENTS */

    /** Return the entire contents of FILE as a byte array.  FILE must
     *  be a normal file.  Throws IllegalArgumentException
     *  in case of problems. */
    public static byte[] readContents(File file) {
        if (!file.isFile()) {
            throw new IllegalArgumentException("must be a normal file");
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException excp) {
            throw new IllegalArgumentException(excp.getMessage());
        }
    }

    /** 将 FILE 的全部内容作为字符串返回。FILE 必须
 *  是一个普通文件。若出现问题，将抛出 IllegalArgumentException
 *  异常。 */
    public static String readContentsAsString(File file) {
        return new String(readContents(file), StandardCharsets.UTF_8);
    }

    /** 将 CONTENTS 中的字节串接到 FILE 的结果写入，
     * 根据需要创建或覆盖。 CONTENTS 中的每个对象可以是
     * 字符串或字节数组。 Throws IllegalArgumentException
     * 以防出现问题。 */
    public static void writeContents(File file, Object... contents) {
        try {
            if (file.isDirectory()) {
                throw
                    new IllegalArgumentException("cannot overwrite directory");
            }
            BufferedOutputStream str =
                new BufferedOutputStream(Files.newOutputStream(file.toPath()));
            for (Object obj : contents) {
                if (obj instanceof byte[]) {
                    str.write((byte[]) obj);
                } else {
                    str.write(((String) obj).getBytes(StandardCharsets.UTF_8));
                }
            }
            str.close();
        } catch (IOException | ClassCastException excp) {
            throw new IllegalArgumentException(excp.getMessage());
        }
    }

    /** 返回从 FILE 读取的类型为 T 的对象，并将其转换为 expectedClass。
     * 在出现问题时投掷IllegalArgumentException。 */
    public static <T extends Serializable> T readObject(File file,
                                                 Class<T> expectedClass) {
        try {
            ObjectInputStream in =
                new ObjectInputStream(new FileInputStream(file));
            T result = expectedClass.cast(in.readObject());
            in.close();
            return result;
        } catch (IOException | ClassCastException
                 | ClassNotFoundException excp) {
            throw new IllegalArgumentException(excp.getMessage());
        }
    }

    /** Write OBJ to FILE. */
    public static void writeObject(File file, Serializable obj) {
        writeContents(file, serialize(obj));
    }

    /* 目录 */

    /** 过滤掉除普通文件外的所有文件。 */
    private static final FilenameFilter PLAIN_FILES =
        new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isFile();
            }
        };

    /**返回目录 DIR 中所有普通文件名称的列表，在
     * 字典序为 Java 字符串。 如果 DIR 有，则返回 null
     * 不表示目录。 */
    public static List<String> plainFilenamesIn(File dir) {
        String[] files = dir.list(PLAIN_FILES);
        if (files == null) {
            return null;
        } else {
            Arrays.sort(files);
            return Arrays.asList(files);
        }
    }

    /** 返回目录 DIR 中所有普通文件名称的列表，在
     * 字典序为 Java 字符串。 如果 DIR 有，则返回 null
     * 不表示目录。 */
    public static List<String> plainFilenamesIn(String dir) {
        return plainFilenamesIn(new File(dir));
    }

    /* OTHER FILE UTILITIES */

    /** Return the concatentation of FIRST and OTHERS into a File designator,
     *  analogous to the {@link Paths.#get(String, String[])}
     *  method. */
    public static File join(String first, String... others) {
        return Paths.get(first, others).toFile();
    }

    /** Return the concatentation of FIRST and OTHERS into a File designator,
     *  analogous to the {@link Paths.#get(String, String[])}
     *  method. */
    public static File join(File first, String... others) {
        return Paths.get(first.getPath(), others).toFile();
    }


    /* SERIALIZATION UTILITIES */

    /** 返回包含 OBJ 序列化内容的字节数组。 */
    public static byte[] serialize(Serializable obj) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(stream);
            objectStream.writeObject(obj);
            objectStream.close();
            return stream.toByteArray();
        } catch (IOException excp) {
            throw error("Internal error serializing commit.");
        }
    }

    /* MESSAGES AND ERROR REPORTING */

    /** 返回一个GitletException，其消息由MSG和ARGS组成为
     * 表示 String.format 方法. */
    public static GitletException error(String msg, Object... args) {
        return new GitletException(String.format(msg, args));
    }

    /** 打印由 MSG 和 ARGS 组成的消息，就像 String.format 一样
     * 方法，后面跟一个换行。 */
    public static void message(String msg, Object... args) {
        System.out.printf(msg, args);
        System.out.println();
    }
}
