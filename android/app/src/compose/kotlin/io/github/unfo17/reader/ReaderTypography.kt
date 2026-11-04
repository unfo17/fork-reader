package io.github.unfo17.reader

import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

/** Complements Readium 3.1.2's CJK CSS, which omits letter spacing, indent and alignment. */
internal object ReaderTypography {
    // Readium forbids negative spacing; reserve a .5 offset to cover the upstream -.5..3 range.
    fun letterSpacingPreference(pixels: Float): Double = pixels.toDouble() + .5
    fun resource(source: Resource, type: MediaType): Resource = TransformingResource(source) { bytes ->
        val charset = type.charset ?: Charsets.UTF_8
        val html = bytes.toString(charset)
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd < 0) Try.success(bytes)
        else Try.success(StringBuilder(html).insert(headEnd, injection).toString().toByteArray(charset))
    }

    // Every resource receives this before pagination, including prefetched chapters. Observe only
    // Readium's root style; do not alter text nodes or the locators used by existing annotations.
    // The SDK serializes letterSpacing as (value / 2) rem with two decimal places. Convert its
    // numeric value back to logical pixels and remove our offset. This preserves .1px at any font size.
    private val injection = """
        <style id="fork-reader-typography" type="text/css"></style>
        <script type="text/javascript">
        //<![CDATA[
        (function(){
          var root=document.documentElement, style=document.getElementById('fork-reader-typography');
          function update(){
            var spacing=parseFloat(root.style.getPropertyValue('--USER__letterSpacing'));
            var prefix=':root[style*="readium-advanced-on"] ';
            var rules=prefix+'p{ text-indent:var(--USER__paraIndent)!important; }'+
              prefix+'body{ text-align:var(--USER__textAlign)!important; }';
            if(isFinite(spacing)) rules+=['div','p','li','h1','h2','h3','h4','h5','h6'].map(function(tag){return prefix+tag;}).join(',')+'{letter-spacing:'+(spacing*2-0.5)+'px!important;}';
            if(style.textContent!==rules) style.textContent=rules;
          }
          new MutationObserver(update).observe(root,{attributes:true,attributeFilter:['style']});
          update();
        })();
        //]]>
        </script>
    """.trimIndent()
}
