"""Create a self-authored EPUB for checking publisher CSS in the native view."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED
import sys
path = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/readium-style-check.epub')
with ZipFile(path, 'w') as book:
    book.writestr('mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
    book.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    book.writestr('OPS/book.opf', '''<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:readium:publisher-style-check:1</dc:identifier><dc:title>EPUB 原书样式检查</dc:title><dc:language>zh-CN</dc:language><meta property="dcterms:modified">2026-11-17T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="css" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>''')
    book.writestr('OPS/style.css', 'h1{color:#d00000;text-align:right;border-bottom:4px double #1640c0} .box{padding:1em;border:3px dotted #1640c0;background:#fff4c9} table{border-collapse:collapse;width:100%} td{border:2px solid #008000;background:#d9f7d9;padding:12px} .small{font-size:70%;font-style:italic}')
    book.writestr('OPS/nav.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">样式检查</a></li><li><a href="two.xhtml">目录跳转检查</a></li></ol></nav></body></html>')
    book.writestr('OPS/one.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>样式检查</title><link rel="stylesheet" href="style.css"/></head><body><h1>红色右对齐标题</h1><div class="box">浅黄色背景、蓝色虚线边框。<p class="small">较小的斜体文字</p></div><table><tr><td>绿色表格 A</td><td>绿色表格 B</td></tr></table><p><ruby>汉字<rt>hàn zì</rt></ruby> 注音应显示在文字上方。</p></body></html>')
    book.writestr('OPS/two.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>目录跳转检查</title><link rel="stylesheet" href="style.css"/></head><body><h1>第二章</h1><p>通过原书目录进入这一章，并在重新打开后续读。</p></body></html>')
print(path)
