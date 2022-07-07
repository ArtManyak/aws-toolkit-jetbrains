// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.editor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.telemetry.CodewhispererAutomatedTriggerType
import software.aws.toolkits.telemetry.CodewhispererTriggerType

class CodeWhispererEnterHandler(private val originalHandler: EditorActionHandler) :
    EditorActionHandler(),
    CodeWhispererAutoTriggerHandler {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?) =
        originalHandler.isEnabled(editor, caret, dataContext)

    override fun executeInCommand(editor: Editor, dataContext: DataContext?) =
        originalHandler.executeInCommand(editor, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (originalHandler.isEnabled(editor, caret, dataContext)) {
            originalHandler.execute(editor, caret, dataContext)
        }
        if (!CodeWhispererService.getInstance().canDoInvocation(editor, CodewhispererTriggerType.AutoTrigger)) {
            return
        }
        performAutomatedTriggerAction(editor, CodewhispererAutomatedTriggerType.Enter)
    }
}