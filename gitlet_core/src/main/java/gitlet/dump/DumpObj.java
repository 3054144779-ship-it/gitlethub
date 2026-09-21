package gitlet.dump;

import gitlet.utils.Utils;

import java.io.File;

/** 一个调试类，其主程序可按以下方式调用：
 * java gitlet.dump.DumpObj FILE...
 * 每个 FILE 是由 Utils.writeObject（或任意文件）生成的文件
 * 包含序列化对象）。 这会简单地读为FILE，
 * 将其反序列化，并在生成的对象上调用转储方法。
 * 该对象必须实现gitlet。可倾倒接口
 * 去上班。 例如，你可以这样定义你的类：
 *
 *        import java.io.Serializable;
 *        import java.util.TreeMap;
 *        class MyClass implements Serializeable, Dumpable {
 *            ...
 *            @Override
 *            public void dump() {
 *               System.out.printf("size: %d%mapping: %s%n", _size, _mapping);
 *            }
 *            ...
 *            int _size;
 *            TreeMap<String, String> _mapping = new TreeMap<>();
 *        }
 *
 *  如图所示，你的转储方法应该打印有用的信息从
 * 你们阶级的物品。
 *  @author P. N. Hilfinger
 */
public class DumpObj {

    /** 反序列化并对每个文件的内容应用转储
     * 在档案中。*/
    public static void main(String... files) {
        for (String fileName : files) {
            Dumpable obj = Utils.readObject(new File(fileName),
                                            Dumpable.class);
            obj.dump();
            System.out.println("---");
        }
    }
}

