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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.string.UTF16String
import com.itsaky.androidide.treesitter.string.UTF16StringFactory
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

open class TsAnalyzeManager(val languageSpec: TsLanguageSpec, var theme: TsTheme) :
    AnalyzeManager {

    var currentReceiver: StyleReceiver? = null
    var reference: ContentReference? = null
    var extraArguments: Bundle? = null
    var thread: TsLooperThread? = null
    var styles = Styles()
    val messageCounter = AtomicInteger()

    fun updateTheme(theme: TsTheme) {
        this.theme = theme
        (styles.spans as LineSpansGenerator?)?.let {
            it.theme = theme
        }
    }

    override fun setReceiver(receiver: StyleReceiver?) {
        currentReceiver = receiver
    }

    override fun reset(content: ContentReference, extraArguments: Bundle) {
        reference = content
        this.extraArguments = extraArguments
        rerun()
    }

    override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
        thread?.handler?.let {
            messageCounter.getAndIncrement()
            it.sendMessage(it.obtainMessage(MSG_MOD, TextModification(start.index, end.index, TSInputEdit(start.index, start.index, end.index, start.toTSPoint(), start.toTSPoint(), end.toTSPoint()), insertedContent.toString())))
        }
        (styles.spans as LineSpansGenerator?)?.apply {
            lineCount = reference!!.lineCount
            tree.edit(TSInputEdit(start.index, start.index, end.index, start.toTSPoint(), start.toTSPoint(), end.toTSPoint()))
        }
    }

    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
        thread?.handler?.let {
            messageCounter.getAndIncrement()
            it.sendMessage(it.obtainMessage(MSG_MOD, TextModification(start.index, end.index, TSInputEdit(start.index, end.index, start.index, start.toTSPoint(), end.toTSPoint(), start.toTSPoint()), null)))
        }
        (styles.spans as LineSpansGenerator?)?.apply {
            lineCount = reference!!.lineCount
            tree.edit(TSInputEdit(start.index, end.index, start.index, start.toTSPoint(), end.toTSPoint(), start.toTSPoint()))
        }
    }

    override fun rerun() {
        messageCounter.set(0)
        thread?.let {
            it.callback = { throw CancellationException() }
            if (it.isAlive) {
                it.handler?.sendMessage(
                    Message.obtain(
                        thread!!.handler,
                        MSG_EXIT
                    )
                )
                it.abort = true
            }
        }
        (styles.spans as LineSpansGenerator?)?.tree?.close()
        styles.spans = null
        val initText = reference?.reference.toString() ?: ""
        thread = TsLooperThread {
            handler!!.apply {
                messageCounter.getAndIncrement()
                sendMessage(obtainMessage(MSG_INIT, initText))
            }
        }.also {
            it.name = "TsDaemon-${nextThreadId()}"
            styles = Styles()
            it.start()
        }
    }

    override fun destroy() {
        thread?.let {
            it.callback = { throw CancellationException() }
            if (it.isAlive) {
                it.handler?.sendMessage(
                    Message.obtain(
                        thread!!.handler,
                        MSG_EXIT
                    )
                )
                it.abort = true
            }
        }
        (styles.spans as LineSpansGenerator?)?.tree?.close()
    }

    companion object {
        private const val MSG_BASE = 11451400
        private const val MSG_INIT = MSG_BASE + 1
        private const val MSG_MOD = MSG_BASE + 2
        private const val MSG_EXIT = MSG_BASE + 3
        @Volatile
        private var threadId = 0

        @Synchronized
        fun nextThreadId() = ++threadId
    }

    inner class TsLooperThread(var callback: TsLooperThread.() -> Unit) : Thread() {

        var handler: Handler? = null
        var looper: Looper? = null
        @Volatile
        var abort: Boolean = false
        val localText: UTF16String = UTF16StringFactory.newString()
        private val parser = TSParser().also {
            it.language = languageSpec.language
        }
        var tree: TSTree? = null
        var handledMessageCount = 0

        fun updateStyles() {
            if (thread == this && handledMessageCount == messageCounter.get()) {
                (styles.spans as LineSpansGenerator?)?.tree?.close()
                styles.spans = LineSpansGenerator(tree!!.copy(), reference!!.lineCount, reference!!, theme, languageSpec)
                currentReceiver?.setStyles(this@TsAnalyzeManager, styles)
            }
        }

        override fun run() {
            Looper.prepare()
            looper = Looper.myLooper()
            handler = object : Handler(looper!!) {
                override fun handleMessage(msg: Message) {
                    super.handleMessage(msg)
                    try {
                        handledMessageCount ++
                        when (msg.what) {
                            MSG_INIT -> {
                                localText.append(msg.obj!! as String)
                                if (!abort && !isInterrupted) {
                                    tree = parser.parseString(localText)
                                    updateStyles()
                                }
                            }

                            MSG_MOD -> {
                                if (!abort && !isInterrupted) {
                                    val modification = msg.obj!! as TextModification
                                    val newText = modification.changedText
                                    val t = tree!!
                                    t.edit(modification.tsEdition)
                                    if (newText == null) {
                                        localText.delete(modification.start, modification.end)
                                    } else {
                                        localText.insert(modification.start, newText)
                                    }
                                    tree = parser.parseString(t, localText)
                                    t.close()
                                    updateStyles()
                                }
                            }

                            MSG_EXIT -> {
                                releaseThreadResources()
                                looper.quit()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("TsAnalyzeManager", "Thread ${currentThread().name} exited with an error", e)
                    }
                }
            }

            try {
                callback()
                Looper.loop()
            } catch (e: CancellationException) {
                releaseThreadResources()
            }
        }

        fun releaseThreadResources() {
            parser.close()
            tree?.close()
            localText.close()
        }

    }

    private data class TextModification(
        val start: Int,
        val end: Int,
        val tsEdition: TSInputEdit,
        /**
         * null for deletion
         */
        val changedText: String?
    )
}