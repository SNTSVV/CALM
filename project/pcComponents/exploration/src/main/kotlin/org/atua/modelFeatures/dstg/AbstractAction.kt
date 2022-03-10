/*
 * ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
 * Copyright (C) 2019 - 2021 University of Luxembourg
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.atua.modelFeatures.dstg

import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget

class AbstractAction private constructor (
        val actionType: AbstractActionType,
        val attributeValuationMap: AttributeValuationMap?=null,
        var window: Window,
        val extra: Any?=null
    ) {
    /*override fun equals(other: Any?): Boolean {
        if (other !is AbstractAction)
            return false
        return this.hashCode() == other.hashCode()
    }*/
    var meaningfulScore = 100
    fun isItemAction(): Boolean {
        return when(actionType) {
            AbstractActionType.ITEM_CLICK,AbstractActionType.ITEM_LONGCLICK,AbstractActionType.ITEM_SELECTED -> true
            else -> false
        }
    }
    fun isWidgetAction(): Boolean {
        return attributeValuationMap!=null
    }

    fun isWebViewAction(): Boolean {
        return isWidgetAction() && attributeValuationMap!!.isWebView()
    }
    fun isLaunchOrReset(): Boolean {
        return actionType == AbstractActionType.LAUNCH_APP || actionType == AbstractActionType.RESET_APP
    }

    fun isCheckableOrTextInput(): Boolean {
        if (attributeValuationMap == null)
            return false
        if (attributeValuationMap.isInputField()) {
            return true
        }
        val className = attributeValuationMap.getClassName()
        return when(className) {
            "android.widget.RadioButton", "android.widget.CheckBox", "android.widget.Switch", "android.widget.ToggleButton", "android.widget.CheckedTextView" -> true
            else -> false
        }
    }

    fun isActionQueue(): Boolean {
        return actionType == AbstractActionType.ACTION_QUEUE
    }

    fun getScore(): Double {
        var actionScore = when(actionType) {
            AbstractActionType.PRESS_BACK -> 0.5
            AbstractActionType.SWIPE -> 0.5
            AbstractActionType.LONGCLICK,AbstractActionType.ITEM_LONGCLICK -> 2.0
            AbstractActionType.CLICK,AbstractActionType.ITEM_CLICK -> 4.0
            else -> 1.0
        }
        if (attributeValuationMap == null)
            return actionScore*meaningfulScore
        return actionScore*meaningfulScore
    }

    fun validateSwipeAction(abstractAction: AbstractAction, guiState: State<*>): Boolean {
        if (!abstractAction.isWidgetAction() || abstractAction.actionType != AbstractActionType.SWIPE)
            return true
        val widgets = abstractAction.attributeValuationMap!!.getGUIWidgets(guiState,window)
        widgets.forEach {w->
            var valid = false
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_FORWARD") }) {
                valid = (abstractAction.extra == "SwipeUp" || abstractAction.extra == "SwipeLeft")
            }
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_BACKWARD") }) {
                valid = (valid || abstractAction.extra == "SwipeDown" || abstractAction.extra == "SwipeRight")
            }
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_RIGHT") }) {
                valid = (valid || abstractAction.extra == "SwipeLeft")
            }
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_LEFT") }) {
                valid = (valid || abstractAction.extra == "SwipeRight")
            }
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_DOWN") }) {
                valid = (valid || abstractAction.extra == "SwipeUp")
            }
            if (w.metaInfo.any { it.contains("ACTION_SCROLL_UP") }) {
                valid = (valid || abstractAction.extra == "SwipeDown")
            }
            if (valid)
                return valid
        }
        return false
    }

    fun updateMeaningfulScore(
        lastInteraction: Interaction<*>,
        newState: State<*>,
        prevState: State<*>,
        atuaMF: ATUAMF
    ) {
        val actionId = lastInteraction.actionId
        val structureUuid = atuaMF.stateStructureHashMap[newState.uid]
        if (atuaMF.statementMF!!.actionIncreasingCoverageTracking[actionId.toString()]?.isNotEmpty() ?: false
            || atuaMF.stateVisitCount[structureUuid] == 1)
            meaningfulScore += 50
        else if (prevState == newState || newState.isHomeScreen)
            meaningfulScore -= 100
        else
            meaningfulScore -= 50
    }

    override fun toString(): String {
        return "$actionType - $attributeValuationMap - $window"
    }
    companion object {
        val abstractActionsByWindow = HashMap<Window,ArrayList<AbstractAction>>()

        fun normalizeActionType(interaction: Interaction<Widget>, prevState: State<*>): AbstractActionType {
            val actionType = interaction.actionType
            var abstractActionType = when (actionType) {
                "Tick" -> AbstractActionType.CLICK
                "ClickEvent" -> AbstractActionType.CLICK
                "LongClickEvent" -> AbstractActionType.LONGCLICK
                else -> AbstractActionType.values().find { it.actionName.equals(actionType) }
            }
            if (abstractActionType == AbstractActionType.CLICK && interaction.targetWidget == null) {
                if (interaction.data.isBlank()) {
                    abstractActionType = AbstractActionType.CLICK_OUTBOUND
                } else {
                    val guiDimension = Helper.computeGuiTreeDimension(prevState)
                    val clickCoordination = Helper.parseCoordinationData(interaction.data)
                    if (clickCoordination.first < guiDimension.leftX || clickCoordination.second < guiDimension.topY) {
                        abstractActionType = AbstractActionType.CLICK_OUTBOUND
                    } else {
                        abstractActionType = AbstractActionType.CLICK
                    }
                }
            }
            if (abstractActionType == AbstractActionType.CLICK && interaction.targetWidget != null && interaction.targetWidget!!.isKeyboard) {
                abstractActionType = AbstractActionType.RANDOM_KEYBOARD
            }
            if (abstractActionType == null) {
                throw Exception("No abstractActionType for $actionType")
            }
            return abstractActionType
        }

        fun computeAbstractActionExtraData(actionType: AbstractActionType, interaction: Interaction<Widget>, guiState: State<Widget>, abstractState: AbstractState, atuaMF: org.atua.modelFeatures.ATUAMF): Any? {
            if (actionType != AbstractActionType.SWIPE) {
                return null
            }
            val swipeData = Helper.parseSwipeData(interaction.data)
            val begin = swipeData[0]!!
            val end = swipeData[1]!!
            val swipeAction = Helper.getSwipeDirection(begin, end)
            return swipeAction
        }

        /**
         * The return abstract action needs to be associated with window'input
         */
        fun getOrCreateAbstractAction(actionType: AbstractActionType,
                                      attributeValuationMap: AttributeValuationMap? = null,
                                      extra: Any? = null,
                                      window: Window
        ): AbstractAction {
            val abstractAction: AbstractAction
            abstractActionsByWindow.putIfAbsent(window, ArrayList())
            val availableActions = abstractActionsByWindow[window]!!.filter {
                it.actionType == actionType
                        && it.attributeValuationMap == attributeValuationMap
                        && it.extra == extra
            }
            val availableAction = availableActions.firstOrNull()
            if (availableAction == null) {
                abstractAction = AbstractAction(
                    actionType = actionType,
                    attributeValuationMap = attributeValuationMap,
                    extra = extra,
                    window = window
                )
                abstractActionsByWindow[window]!!.add(abstractAction)

            } else {
                abstractAction = availableAction
            }
            return abstractAction
        }

        fun getOrCreateAbstractAction(actionType: AbstractActionType,
                                      interaction: Interaction<Widget>,
                                      guiState: State<Widget>,
                                      abstractState: AbstractState,
                                      attributeValuationMap: AttributeValuationMap?,
                                      atuaMF: org.atua.modelFeatures.ATUAMF
        ): AbstractAction {
            val abstractAction: AbstractAction
            val actionData = computeAbstractActionExtraData(actionType, interaction, guiState, abstractState, atuaMF)
            abstractActionsByWindow.putIfAbsent(abstractState.window, ArrayList())
            val availableActions = abstractActionsByWindow[abstractState.window]!!.filter {
                it.actionType == actionType
                        && it.attributeValuationMap == attributeValuationMap
                        && it.extra == actionData
            }
            val availableAction = availableActions.firstOrNull()
            if (availableAction == null) {
                abstractAction = AbstractAction(
                        actionType = actionType,
                        attributeValuationMap = attributeValuationMap,
                        extra = actionData,
                        window = abstractState.window
                )
                abstractActionsByWindow[abstractState.window]!!.add(abstractAction)
                abstractState.addAction(abstractAction)
            } else {
                abstractAction = availableAction
                abstractState.addAction(abstractAction)
            }
            return abstractAction
        }
    }

}

enum class  AbstractActionType(val actionName: String) {
    CLICK("Click"),
    LONGCLICK("LongClick"),
    ITEM_CLICK("ItemClick"),
    ITEM_LONGCLICK("ItemLongClick"),
    ITEM_SELECTED("ItemSelected"),
    SWIPE("Swipe"),
    PRESS_BACK("PressBack"),
    PRESS_MENU("PressMenu"),
    ROTATE_UI("RotateUI"),
    CLOSE_KEYBOARD("CloseKeyboard"),
    TEXT_INSERT("TextInsert"),
    MINIMIZE_MAXIMIZE("MinimizeMaximize"),
    SEND_INTENT("CallIntent"),
    RANDOM_CLICK("RandomClick"),
    CLICK_OUTBOUND("ClickOutbound"),
    ENABLE_DATA("EnableData"),
    DISABLE_DATA("DisableData"),
    LAUNCH_APP("LaunchApp"),
    RESET_APP("ResetApp"),
    ACTION_QUEUE("ActionQueue"),
    PRESS_HOME("PressHome"),
    RANDOM_KEYBOARD("RandomKeyboard"),
    UNKNOWN("Underived"),
    FAKE_ACTION("FakeAction"),
    TERMINATE("Terminate"),
    WAIT("FetchGUI")
}