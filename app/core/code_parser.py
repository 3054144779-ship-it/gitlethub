"""
多语言代码解析器
主流语言: Java, Python, JavaScript, TypeScript, Go, Rust
系统级语言: C, C++
Web 语言: Ruby, PHP, C#, HTML, CSS, JSON
"""
from typing import Optional, Union, List
from pathlib import Path
from tree_sitter import Language, Parser, Node

class CodeParser:
    """
    多语言代码解析器
    提供方法:
    detect_language(file_path), 检测代码语言
    parse(source, language), 解析代码
    parse_file(file_path), 解析文件
    find_nodes(node, node_type), 查找节点
    find_nodes_by_pattern(node, pattern), 查找节点
    get_node_text(node), 获取节点文本
    get_supported_languages(), 获取支持的语言
    print_tree(node), 打印树结构
    ast_split(node), AST 切分
    """

    # 文件扩展名到语言名称的映射
    LANGUAGE_MAP = {
        '.java': 'java',
        '.py': 'python',
        '.js': 'javascript',
        '.jsx': 'javascript',
        '.ts': 'typescript',
        '.tsx': 'typescript',
        '.go': 'go',
        '.rs': 'rust',
        '.c': 'c',
        '.h': 'c',
        '.cpp': 'cpp',
        '.cxx': 'cpp',
        '.cc': 'cpp',
        '.hpp': 'cpp',
        '.hxx': 'cpp',
        '.hh': 'cpp',
        '.rb': 'ruby',
        '.php': 'php',
        '.cs': 'c_sharp',
        '.html': 'html',
        '.css': 'css',
        '.json': 'json',
    }

    # 定义每种语言中"有意义"的节点类型
    semantic_node_types = {
        'java': ['method_declaration', 'class_declaration', 'interface_declaration'],
        'python': ['function_definition', 'class_definition'],
        'javascript': ['function_declaration', 'class_declaration', 'arrow_function'],
        'typescript': ['function_declaration', 'class_declaration', 'arrow_function'],
        'go': ['function_declaration', 'method_declaration', 'type_declaration'],
        'rust': ['function_item', 'impl_item', 'struct_item'],
        'c': ['function_definition', 'struct_specifier'],
        'cpp': ['function_definition', 'class_specifier', 'struct_specifier'],
    }

    def __init__(self):
        """初始化代码解析器"""
        self.languages = {}
        self.parsers = {}
        self._load_all_languages()

    def _load_all_languages(self):
        """动态加载所有支持的语言"""
        language_modules = {
            'java': 'tree_sitter_java',
            'python': 'tree_sitter_python',
            'javascript': 'tree_sitter_javascript',
            'typescript': 'tree_sitter_typescript',
            'go': 'tree_sitter_go',
            'rust': 'tree_sitter_rust',
            'c': 'tree_sitter_c',
            'cpp': 'tree_sitter_cpp',
            'ruby': 'tree_sitter_ruby',
            'php': 'tree_sitter_php',
            'c_sharp': 'tree_sitter_c_sharp',
            'html': 'tree_sitter_html',
            'css': 'tree_sitter_css',
            'json': 'tree_sitter_json',
        }

        for lang_name, module_name in language_modules.items():
            try:
                module = __import__(module_name, fromlist=['language'])
                language = Language(module.language())
                self.languages[lang_name] = language
                self.parsers[lang_name] = Parser(language)
            except (ImportError, AttributeError) as e:
                print(f"警告: 无法加载语言 {lang_name}: {e}")
                pass

    def detect_language(self, file_path: Optional[str] = None,
                       code: Optional[str] = None,
                       extension: Optional[str] = None) -> Optional[str]:
        """
        检测代码语言

        Args:
            file_path: 文件路径
            code: 代码字符串（通过文件头判断）
            extension: 文件扩展名

        Returns:
            语言名称，如 'java', 'python' 等
        """
        # 优先使用文件扩展名
        if file_path:
            ext = Path(file_path).suffix.lower()
            if ext in self.LANGUAGE_MAP:
                return self.LANGUAGE_MAP[ext]

        # 使用提供的扩展名
        if extension:
            ext = extension.lower() if extension.startswith('.') else f'.{extension.lower()}'
            if ext in self.LANGUAGE_MAP:
                return self.LANGUAGE_MAP[ext]

        # 通过代码内容判断（简单的启发式判断）
        if code:
            code_preview = code[:500].strip()

            # Python
            if code_preview.startswith(('def ', 'class ', 'import ', 'from ')) or \
               'def ' in code_preview[:100] or 'import ' in code_preview[:100]:
                if 'public class' not in code_preview:
                    return 'python'

            # Java
            if 'public class' in code_preview or 'public static void main' in code_preview:
                return 'java'

            # Go
            if code_preview.startswith('package ') or 'func main()' in code_preview:
                return 'go'

            # Rust
            if 'fn main()' in code_preview or code_preview.startswith('use ') or \
               'impl ' in code_preview:
                return 'rust'

            # JavaScript/TypeScript
            if 'function ' in code_preview or 'const ' in code_preview or \
               'let ' in code_preview or 'var ' in code_preview:
                if ': type' in code_preview or 'interface ' in code_preview or \
                   'type ' in code_preview:
                    return 'typescript'
                return 'javascript'

            # TypeScript
            if 'interface ' in code_preview or 'type ' in code_preview:
                return 'typescript'

            # C#
            if 'namespace ' in code_preview or 'using System' in code_preview:
                return 'c_sharp'

            # Ruby
            if code_preview.startswith('require ') or 'def ' in code_preview or \
               'class ' in code_preview:
                if 'public ' not in code_preview:
                    return 'ruby'

            # PHP
            if code_preview.startswith('<?php') or 'namespace ' in code_preview:
                return 'php'

            # C/C++
            if '#include' in code_preview or 'int main(' in code_preview:
                if 'class ' in code_preview or 'namespace ' in code_preview:
                    return 'cpp'
                return 'c'

        return None

    def parse(self, source: Union[str, bytes], language: Optional[str] = None,
              file_path: Optional[str] = None) -> Optional[Node]:
        """
        解析代码

        Args:
            source: 代码内容（字符串或字节）
            language: 语言名称，如果不提供则自动检测
            file_path: 文件路径（用于语言检测）

        Returns:
            AST 根节点
        """
        # 自动检测语言
        if not language:
            if file_path:
                language = self.detect_language(file_path=file_path)
            else:
                code_str = source if isinstance(source, str) else source.decode('utf-8')
                language = self.detect_language(code=code_str)

        if not language:
            raise ValueError("无法检测语言，请明确指定 language 参数")

        if language not in self.parsers:
            raise ValueError(f"不支持的语言: {language}，请先安装对应的 tree-sitter 包")

        # 转换为字节
        if isinstance(source, str):
            source_bytes = source.encode('utf-8')
        else:
            source_bytes = source

        # 解析代码
        parser = self.parsers[language]
        tree = parser.parse(source_bytes)

        return tree.root_node

    def parse_file(self, file_path: str) -> Optional[Node]:
        """
        解析文件

        Args:
            file_path: 文件路径

        Returns:
            AST 根节点
        """
        with open(file_path, 'rb') as f:
            source = f.read()

        return self.parse(source, file_path=file_path)

    def find_nodes(self, root_node: Node, node_type: str) -> list:
        """
        查找所有指定类型的节点

        Args:
            root_node: 根节点
            node_type: 节点类型

        Returns:
            节点列表
        """
        results = []

        def traverse(node):
            if node.type == node_type:
                results.append(node)
            for child in node.children:
                traverse(child)

        traverse(root_node)
        return results

    def find_nodes_by_pattern(self, root_node: Node, predicate: callable) -> list:
        """
        根据条件查找节点

        Args:
            root_node: 根节点
            predicate: 判断函数，接受节点参数，返回布尔值

        Returns:
            节点列表
        """
        results = []

        def traverse(node):
            if predicate(node):
                results.append(node)
            for child in node.children:
                traverse(child)

        traverse(root_node)
        return results

    def get_node_text(self, node: Node) -> str:
        """
        获取节点的文本内容

        Args:
            node: 节点

        Returns:
            文本内容
        """
        return node.text.decode('utf-8') if node.text else ''

    def get_supported_languages(self) -> list:
        """
        获取当前支持的所有语言

        Returns:
            语言名称列表
        """
        return list(self.languages.keys())

    def print_tree(self, node: Node, indent: int = 0, max_depth: int = 3):
        """
        打印 AST 树（调试用）

        Args:
            node: 节点
            indent: 缩进级别
            max_depth: 最大深度
        """
        if indent > max_depth:
            return

        text_preview = node.text[:50] if node.text else ''
        print("  " * indent + f"{node.type}: {text_preview}")

        for child in node.children:
            self.print_tree(child, indent + 1, max_depth)

    def find_semantic_nodes(self, root: Node, language: str) -> list[Node]:
        """
        按语言查找语义节点（函数、类等）
        Args:
            root: 根节点
            language: 语言名称

        Returns:
            语义节点列表
        """
        target_types = self.semantic_node_types.get(language, [])
        if not target_types:
            return [root]

        nodes = []
        for node_type in target_types:
            nodes.extend(self.find_nodes(root, node_type))

        return nodes if nodes else [root]

    def ast_split_from_root(self, root: Node, language: str, file_path: str = "") -> List[str]:
        """
        按 AST 语义切分代码，提取函数、类等独立单元的文本
        Args:
            root: 根节点
            language: 语言名称
            file_path: 文件路径

        Returns:
            代码块列表
        """
        nodes = self.find_semantic_nodes(root, language)
        chunks = []
        for node in nodes:
            text = self.get_node_text(node)
            if text.strip():
                chunks.append(text)
        return chunks

    def ast_split(self, nodes: List[Node]) -> List[str]:
        """
        按 AST 语义切分代码，提取函数、类等独立单元的文本
        Args:
            :param nodes:
        Returns:
            代码块列表
        """
        chunks = []
        for node in nodes:
            text = self.get_node_text(node)
            if text.strip():
                chunks.append(text)
        return chunks


# 便捷函数
def parse_code(code: str, language: Optional[str] = None, file_path: Optional[str] = None):
    """
    快速解析代码

    Args:
        code: 代码内容
        language: 语言名称
        file_path: 文件路径

    Returns:
        AST 根节点
    """
    parser = CodeParser()
    return parser.parse(code, language=language, file_path=file_path)


def parse_file(file_path: str):
    """
    快速解析文件

    Args:
        file_path: 文件路径

    Returns:
        AST 根节点
    """
    parser = CodeParser()
    return parser.parse_file(file_path)

# 使用示例
if __name__ == "__main__":
    # 创建解析器
    parser = CodeParser()

    print(f"支持的语言: {parser.get_supported_languages()}\n")

    # 示例1: 解析 Java 代码
    java_code = """
    public class HelloWorld {
        public static void main(String[] args) {
            System.out.println("Hello, World!");
        }
    }
    """

    print("=== 解析 Java 代码 ===")
    root = parser.parse(java_code, language='java')
    print(f"根节点类型: {root.type}")
    print(f"检测到的语言: {parser.detect_language(code=java_code)}")

    # 查找所有类声明
    classes = parser.find_nodes(root, 'class_declaration')
    print(f"找到 {len(classes)} 个类")

    # 示例2: 解析 Python 代码
    python_code = """
def greet(name):
    print(f"Hello, {name}!")

class Person:
    def __init__(self, name):
        self.name = name
"""

    print("\n=== 解析 Python 代码 ===")
    root = parser.parse(python_code, language='python')
    print(f"根节点类型: {root.type}")

    # 查找所有函数定义
    functions = parser.find_nodes(root, 'function_definition')
    print(f"找到 {len(functions)} 个函数")

    # 示例3: 自动检测语言
    print("\n=== 自动检测语言 ===")
    print(f"Java 代码检测: {parser.detect_language(code=java_code)}")
    print(f"Python 代码检测: {parser.detect_language(code=python_code)}")

    go_code = "package main\n\nfunc main() {\n\tfmt.Println(\"Hello\")\n}"
    print(f"Go 代码检测: {parser.detect_language(code=go_code)}")
