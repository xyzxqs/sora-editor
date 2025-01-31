/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2022  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.editor.ts

import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryError
import java.io.Closeable

class TsLanguageSpec(
    val language: TSLanguage,
    highlightScmSource: String,
    localsScmSource: String = "",
    localsCaptureSpec: LocalsCaptureSpec = LocalsCaptureSpec.DEFAULT
) : Closeable {

    val querySource = localsScmSource + "\n" + highlightScmSource

    val highlightScmOffset = localsScmSource.encodeToByteArray().size + 1

    val tsQuery = TSQuery(language, querySource)

    val highlightPatternOffset: Int

    val localsDefinitionIndices = mutableListOf<Int>()

    val localsReferenceIndices = mutableListOf<Int>()

    val localsScopeIndices = mutableListOf<Int>()

    val localsDefinitionValueIndices = mutableListOf<Int>()

    var closed = false

    init {
        querySource.forEach {
            if (it > 0xFF.toChar()) {
                throw IllegalArgumentException("use non-ASCII characters in scm source is unexpected")
            }
        }
        if (tsQuery.errorType != TSQueryError.None) {
            val region = if (tsQuery.errorOffset < highlightScmOffset) "locals" else "highlight"
            val offset = if (tsQuery.errorOffset < highlightScmOffset) tsQuery.errorOffset else tsQuery.errorOffset - highlightScmOffset
            throw IllegalArgumentException("bad scm sources: error ${tsQuery.errorType.name} occurs in $region range at offset $offset")
        }
        var highlightOffset = 0
        for (i in 0 until tsQuery.patternCount) {
            if (tsQuery.getStartByteForPattern(i) < highlightScmOffset) {
                highlightOffset ++
            }
            val name = tsQuery.getCaptureNameForId(i)
            if (localsCaptureSpec.isDefinitionCapture(name)) {
                localsDefinitionIndices.add(i)
            } else if (localsCaptureSpec.isReferenceCapture(name)) {
                localsReferenceIndices.add(i)
            } else if (localsCaptureSpec.isScopeCapture(name)) {
                localsScopeIndices.add(i)
            } else if (localsCaptureSpec.isDefinitionValueCapture(name)) {
                localsDefinitionValueIndices.add(i)
            }
        }
        highlightPatternOffset = highlightOffset
    }

    override fun close() {
        tsQuery.close()
        closed = true
    }

}