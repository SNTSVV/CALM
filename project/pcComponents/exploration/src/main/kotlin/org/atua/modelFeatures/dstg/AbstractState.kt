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

import org.atua.calm.modelReuse.ModelHistoryInformation
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.dstg.reducer.WidgetReducer
import org.atua.modelFeatures.ewtg.*
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.mapping.EWTG_DSTGMapping
import org.droidmate.explorationModel.emptyUUID
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.toUUID
import java.io.File
import java.nio.file.Path
import java.util.*

open class AbstractState(
    val activity: String,
    val attributeValuationMaps: ArrayList<AttributeValuationMap> = arrayListOf(),
    val avmCardinalities: HashMap<AttributeValuationMap, Cardinality>,
    val guiStates: ArrayList<State<*>> = ArrayList(),
    var window: Window,
    val EWTGWidgetMapping: HashMap<AttributeValuationMap, EWTGWidget> = HashMap(),
    val abstractTransitions: HashSet<AbstractTransition> = HashSet(),
    private val inputMappings: HashMap<AbstractAction, HashSet<Input>> = HashMap(),
    val isHomeScreen: Boolean = false,
    val isOpeningKeyboard: Boolean = false,
    val isRequestRuntimePermissionDialogBox: Boolean = false,
    val isAppHasStoppedDialogBox: Boolean = false,
    val isOutOfApplication: Boolean = false,
    var isOpeningMenus: Boolean = false,
    var rotation: org.atua.modelFeatures.Rotation,
    var loadedFromModel: Boolean = false,
    var modelVersion: ModelVersion = ModelVersion.RUNNING,
    reuseAbstractStateId: UUID? = null
) {
    var ignored: Boolean = false
    private val actionCount = HashMap<AbstractAction, Int>()
    val abstractStateId: String
    var hashCode: Int = 0
    var isInitalState = false

    var hasOptionsMenu: Boolean = true
    var shouldNotCloseKeyboard: Boolean = false

    init {
        window.mappedStates.add(this)
        attributeValuationMaps.forEach {
            it.captured = true
        }

        countAVMFrequency()
        hashCode = computeAbstractStateHashCode(attributeValuationMaps, avmCardinalities, window, rotation)
        abstractStateIdByWindow.putIfAbsent(window, HashSet())
        if (reuseAbstractStateId != null
            && abstractStateIdByWindow[window]!!.contains(reuseAbstractStateId)
        ) {
            abstractStateId = reuseAbstractStateId.toString()
            abstractStateIdByWindow.get(window)!!.add(reuseAbstractStateId)
        } else {
            var id: Int = hashCode
            while (abstractStateIdByWindow[window]!!.contains(id.toUUID())) {
                id = id + 1
            }
            abstractStateId = "${id.toUUID()}"
            abstractStateIdByWindow.get(window)!!.add(id.toUUID())
        }
    }

    fun associateAbstractActionWithInputs(abstractAction: AbstractAction, input: Input) {
        inputMappings.putIfAbsent(abstractAction, HashSet())
        EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions.putIfAbsent(abstractAction, ArrayList())
        if (!inputMappings[abstractAction]!!.contains(input)) {
            inputMappings[abstractAction]!!.add(input)

        }
        if (!EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions[abstractAction]!!.contains(input))
            EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions[abstractAction]!!.add(input)
        if (this !is VirtualAbstractState && this.guiStates.isNotEmpty()) {
            input.witnessed = true
        }
    }

    private fun isValidAction(abstractAction: AbstractAction): Boolean {
        if (!abstractAction.isWidgetAction()) {
            return actionCount.containsKey(abstractAction)
        }
        return abstractAction.attributeValuationMap!!.containsAction(abstractAction)
    }

    fun isAbstractActionMappedWithInputs(abstractAction: AbstractAction): Boolean {
        if (inputMappings.containsKey(abstractAction))
            return true
        return false
    }

    fun getAbstractActionsWithSpecificInputs(input: Input): List<AbstractAction> {
        return inputMappings.filter { it.value.contains(input) }.keys.toList()
    }

    fun getInputsByAbstractAction(abstractAction: AbstractAction): List<Input> {
        return inputMappings[abstractAction]?.toList() ?: emptyList<Input>()
    }

    fun updateHashCode() {
        hashCode = computeAbstractStateHashCode(attributeValuationMaps, avmCardinalities, window, rotation)
    }

    fun countAVMFrequency() {
        if (!AbstractStateManager.INSTANCE.attrValSetsFrequency.containsKey(window)) {
            AbstractStateManager.INSTANCE.attrValSetsFrequency.put(window, HashMap())
        }
        val widgetGroupFrequency = AbstractStateManager.INSTANCE.attrValSetsFrequency[window]!!
        attributeValuationMaps.forEach {
            if (!widgetGroupFrequency.containsKey(it)) {
                widgetGroupFrequency.put(it, 1)
            } else {
                widgetGroupFrequency[it] = widgetGroupFrequency[it]!! + 1
            }
        }
    }

    fun isStructuralEqual(other: AbstractState): Boolean {
        return hashCode == other.hashCode
    }

    fun initAction() {
        val resetAction = AbstractAction.getOrCreateAbstractAction(
            actionType = AbstractActionType.RESET_APP,
            attributeValuationMap = null,
            extra = null,
            window = Launcher.getOrCreateNode()
        )
        actionCount.put(resetAction, 0)
        var input = Input.getOrCreateInput(
            HashSet(),
            EventType.resetApp.toString(),
            null,
            Launcher.getOrCreateNode(),
            true,
            modelVersion
        )
        associateAbstractActionWithInputs(resetAction, input!!)
        val launchAction = AbstractAction.getOrCreateAbstractAction(
            actionType = AbstractActionType.LAUNCH_APP,
            attributeValuationMap = null,
            extra = null,
            window = window
        )
        actionCount.put(launchAction, 0)
        input = Input.getOrCreateInput(
            HashSet(),
            EventType.implicit_launch_event.toString(),
            null,
            window,
            true,
            modelVersion
        )
        associateAbstractActionWithInputs(launchAction, input!!)
        if (isOpeningKeyboard) {
            val closeKeyboardAction = AbstractAction.getOrCreateAbstractAction(
                actionType = AbstractActionType.CLOSE_KEYBOARD,
                attributeValuationMap = null,
                extra = null,
                window = window
            )
            actionCount.put(closeKeyboardAction, 0)
            input =
                Input.getOrCreateInput(HashSet(), EventType.closeKeyboard.toString(), null, window, true, modelVersion)
            associateAbstractActionWithInputs(closeKeyboardAction, input!!)
        } else {
            val pressBackAction = AbstractAction.getOrCreateAbstractAction(
                actionType = AbstractActionType.PRESS_BACK,
                window = window,
                attributeValuationMap = null,
                extra = null
            )
            actionCount.put(pressBackAction, 0)
            input = Input.getOrCreateInput(HashSet(), EventType.press_back.toString(), null, window, true, modelVersion)
            associateAbstractActionWithInputs(pressBackAction, input!!)
            /*if (!this.isMenusOpened && this.hasOptionsMenu && this.window !is Dialog) {
                val pressMenuAction = AbstractAction(
                        actionType = AbstractActionType.PRESS_MENU
                )
                actionCount.put(pressMenuAction, 0)
            }*/
            if (!this.isOpeningMenus) {
                val minmaxAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.MINIMIZE_MAXIMIZE,
                    window = window
                )
                actionCount.put(minmaxAction, 0)
                input = Input.getOrCreateInput(
                    HashSet(),
                    EventType.implicit_lifecycle_event.toString(),
                    null,
                    window,
                    true,
                    modelVersion
                )
                associateAbstractActionWithInputs(minmaxAction, input!!)
                if (window is Activity || rotation == org.atua.modelFeatures.Rotation.LANDSCAPE) {
                    val rotationAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.ROTATE_UI,
                        window = window
                    )
                    actionCount.put(rotationAction, 0)
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.implicit_rotate_event.toString(),
                        null,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(rotationAction, input!!)
                }
            }
            if (window is Dialog) {
                val clickOutDialog = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.CLICK_OUTBOUND,
                    window = window
                )
                actionCount.put(clickOutDialog, 0)
                input = Input.getOrCreateInput(HashSet(), EventType.click.toString(), null, window, true, modelVersion)
                associateAbstractActionWithInputs(clickOutDialog, input!!)
            }
        }
        EWTGWidgetMapping.forEach { avm, ewgtwidget ->
            avm.getAvailableActions().forEach { abstractAction ->
                if (abstractAction.actionType == AbstractActionType.CLICK) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.click.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
                if (abstractAction.actionType == AbstractActionType.LONGCLICK) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.long_click.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
                if (abstractAction.actionType == AbstractActionType.ITEM_CLICK) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.item_click.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
                if (abstractAction.actionType == AbstractActionType.ITEM_LONGCLICK) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.item_long_click.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
                if (abstractAction.actionType == AbstractActionType.TEXT_INSERT) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.enter_text.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
                if (abstractAction.actionType == AbstractActionType.SWIPE) {
                    input = Input.getOrCreateInput(
                        HashSet(),
                        EventType.swipe.toString(),
                        ewgtwidget,
                        window,
                        true,
                        modelVersion
                    )
                    associateAbstractActionWithInputs(abstractAction, input!!)
                }
            }
        }

    }

    fun addAction(action: AbstractAction) {
        if (action.actionType == AbstractActionType.PRESS_HOME) {
            return
        }
        if (action.attributeValuationMap == null) {
            if (action.actionType == AbstractActionType.CLICK)
                return
            if (!actionCount.containsKey(action)) {
                actionCount[action] = 0
            }
            return
        }
        if (action.attributeValuationMap.getActionCount(action) == -1) {
            action.attributeValuationMap.setActionCount(action, 0)
        }
    }

    fun getAttributeValuationSet(
        widget: Widget,
        guiState: State<*>,
        atuaMF: org.atua.modelFeatures.ATUAMF
    ): AttributeValuationMap? {
        if (!guiStates.contains(guiState))
            return null
        val mappedWidget_AttributeValuationSet = AttributeValuationMap.allWidgetAVMHashMap[window]
        if (mappedWidget_AttributeValuationSet == null)
            return null
        val mappedAttributeValuationSet = mappedWidget_AttributeValuationSet.get(widget)
        if (mappedAttributeValuationSet == null) {
            if (mappedWidget_AttributeValuationSet.any { it.key.uid == widget.uid }) {
                val guiTreeRectangle = Helper.computeGuiTreeDimension(guiState)
                var isOptionsMenu = if (!Helper.isDialog(this.rotation, guiTreeRectangle, guiState, atuaMF))
                    Helper.isOptionsMenuLayout(guiState)
                else
                    false
                val reducedAttributePath = WidgetReducer.reduce(
                    widget,
                    guiState,
                    isOptionsMenu,
                    guiTreeRectangle,
                    window,
                    rotation,
                    atuaMF,
                    HashMap(),
                    HashMap()
                )
                val ewtgWidget = WindowManager.instance.guiWidgetEWTGWidgetMappingByWindow.get(window)!!.get(widget)
                val attributeValuationSet = attributeValuationMaps.find {
                    it.haveTheSameAttributePath(reducedAttributePath, window)
                }
                return attributeValuationSet
            }
            if (Helper.hasParentWithType(widget, guiState, "WebView")) {
                val webViewWidget = Helper.tryGetParentHavingClassName(widget, guiState, "WebView")
                if (webViewWidget != null) {
                    return getAttributeValuationSet(webViewWidget, guiState, atuaMF)
                }
            }
            return null
        } else {

        }
        val attributeValuationSet = attributeValuationMaps.find {
            it.haveTheSameAttributePath(mappedAttributeValuationSet, window)
        }
        return attributeValuationSet
    }

    fun getAvailableActions(currentState: State<*>? = null): List<AbstractAction> {
        val allActions = ArrayList<AbstractAction>()
        allActions.addAll(inputMappings.keys)
        //allActions.addAll(attributeValuationMaps.map { it.getAvailableActions() }.flatten())
        //
        if (currentState != null) {
            allActions.removeIf { abstractAction ->
                abstractAction.isWidgetAction()
                        && abstractAction.actionType == AbstractActionType.SWIPE
                        && !abstractAction.validateSwipeAction(abstractAction, currentState)
            }
        }
        return allActions
    }

    fun getUnExercisedActions2(currentState: State<*>?): List<AbstractAction> {
        val potentialActions = getAvailableActions(currentState)
            .filter {
                it.meaningfulScore > 0
                        && it.actionType != AbstractActionType.FAKE_ACTION
                        && it.actionType != AbstractActionType.LAUNCH_APP
                        && it.actionType != AbstractActionType.RESET_APP
                        && it.actionType != AbstractActionType.ENABLE_DATA
                        && it.actionType != AbstractActionType.DISABLE_DATA
                        && it.actionType != AbstractActionType.WAIT
                        && it.actionType != AbstractActionType.SEND_INTENT
                        && !AbstractStateManager.INSTANCE.goBackAbstractActions.contains(it)
            }
            .filterNot { (it.actionType == AbstractActionType.CLICK && !it.isWidgetAction()) }
        return potentialActions
    }

    fun getUnExercisedActions(
        currentState: State<*>?,
        atuaMF: org.atua.modelFeatures.ATUAMF
    ): List<AbstractAction> {
        val unexcerisedActions = HashSet<AbstractAction>()
        //use hashmap to optimize the performance of finding widget
        val widget_WidgetGroupMap = HashMap<Widget, AttributeValuationMap>()
        if (currentState != null) {
            Helper.getVisibleWidgetsForAbstraction(currentState).forEach { w ->
                val wg = this.getAttributeValuationSet(w, currentState, atuaMF)
                if (wg != null)
                    widget_WidgetGroupMap.put(w, wg)
            }
        }
/*        val toTestAction = HashSet<AbstractAction>()
        toTestAction.addAll(actionCount.filter {
            it.key.actionType != AbstractActionType.FAKE_ACTION
                    && it.key.actionType != AbstractActionType.LAUNCH_APP
                    && it.key.actionType != AbstractActionType.RESET_APP
                    && it.key.actionType != AbstractActionType.ENABLE_DATA
                    && it.key.actionType != AbstractActionType.DISABLE_DATA
                    && it.key.actionType != AbstractActionType.WAIT
                    && !it.key.isWidgetAction()
                    && it.key.actionType != AbstractActionType.CLICK
                    && it.key.actionType != AbstractActionType.LONGCLICK
                    && it.key.actionType != AbstractActionType.SEND_INTENT
                    && it.key.actionType != AbstractActionType.PRESS_MENU
        }.map { it.key })
        attributeValuationMaps.forEach {
            toTestAction.addAll(it.actionCount.keys)
        }
        val actionsWithTransitions = abstractTransitions.map { it.abstractAction }
        unexcerisedActions.addAll(
            toTestAction.filter { action ->
                !actionsWithTransitions.contains(action) }
        )
        return unexcerisedActions.toList()*/
        unexcerisedActions.addAll(actionCount.filter {
            it.key.actionType != AbstractActionType.FAKE_ACTION
                    && !it.key.isWidgetAction()
                    && it.key.actionType != AbstractActionType.LAUNCH_APP
                    && it.key.actionType != AbstractActionType.RESET_APP
                    && it.key.actionType != AbstractActionType.ENABLE_DATA
                    && it.key.actionType != AbstractActionType.DISABLE_DATA
                    && it.key.actionType != AbstractActionType.WAIT
                    && it.key.actionType != AbstractActionType.CLICK
                    && it.key.actionType != AbstractActionType.LONGCLICK
                    && it.key.actionType != AbstractActionType.SEND_INTENT
                    && it.key.actionType != AbstractActionType.CLOSE_KEYBOARD
                    && getInputsByAbstractAction(it.key).any { it.meaningfulScore > 0
                        && it.exerciseCount==0
                         &&(!atuaMF.reuseBaseModel
                    || ModelHistoryInformation.INSTANCE.inputUsefulness[it]?.second?:1>0)}
                    && it.value==0
        }.map { it.key })
        val widgetActionCounts = if (currentState != null) {
            widget_WidgetGroupMap.values.filter { !it.isUserLikeInput(this) }.distinct()
                .map { w -> w.getAvailableActionsWithExercisingCount() }
        } else {
            attributeValuationMaps
                .filter { !it.isUserLikeInput(this) }.map { w -> w.getAvailableActionsWithExercisingCount() }
        }
        widgetActionCounts.forEach {
            val actions = it.filterNot {
                (it.key.actionType == AbstractActionType.ITEM_CLICK)
                        || (it.key.actionType == AbstractActionType.ITEM_LONGCLICK)
            }.filter {
                it.value == 0
                        || (avmCardinalities.get(it.key.attributeValuationMap) == Cardinality.MANY && it.value <= 3)
                        || (it.key.isWebViewAction() && it.value <= 5)
            }.map { it.key }
            unexcerisedActions.addAll(actions)
        }

        if (currentState != null) {
            unexcerisedActions.removeIf { abstractAction ->
                abstractAction.isWidgetAction()
                        && abstractAction.actionType == AbstractActionType.SWIPE
                        && !abstractAction.validateSwipeAction(abstractAction, currentState)
            }
        }
        unexcerisedActions.removeIf { abstractAction ->
            inputMappings[abstractAction]?.any {
                it.isUseless == true &&
                        (!abstractAction.isWidgetAction() || it.exerciseCount > 0)
            } ?: false
        }
        return unexcerisedActions.toList()
    }

    private fun hasLongClickableSubItems(
        attributeValuationMap: AttributeValuationMap,
        currentState: State<*>?
    ): Boolean {
        if (currentState == null)
            return false
        val guiWidgets = attributeValuationMap.getGUIWidgets(currentState, window)
        if (guiWidgets.isNotEmpty()) {
            guiWidgets.forEach {
                if (Helper.haveLongClickableChild(currentState.visibleTargets, it))
                    return true
            }
        }
        return false
    }

    private fun hasClickableSubItems(attributeValuationMap: AttributeValuationMap, currentState: State<*>?): Boolean {
        if (currentState == null)
            return false
        val guiWidgets = attributeValuationMap.getGUIWidgets(currentState, window)
        if (guiWidgets.isNotEmpty()) {
            guiWidgets.forEach {
                if (Helper.haveClickableChild(currentState.visibleTargets, it))
                    return true
            }
        }
        return false
    }


    override fun toString(): String {
        return "AbstractState[${this.abstractStateId}]-${window}-isKeyboardOpening${isOpeningKeyboard}-isMenuOpening:${isOpeningMenus}"
    }


    fun setActionCount(action: AbstractAction, count: Int) {
        if (action.attributeValuationMap == null) {
            actionCount[action] = count
            return
        }
        if (action.attributeValuationMap.getActionCount(action) == -1) {
            action.attributeValuationMap.setActionCount(action, count)
        }
    }

    fun increaseActionCount2(abstractAction: AbstractAction, updateSimilarAbstractStates: Boolean) {
        this.increaseActionCount(abstractAction)
        /* if (updateSimilarAbstractStates) {
             if (!abstractAction.isWidgetAction()) {
                 AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                     it != this
                             && it.window == this.window
                 }.forEach {
                     it.increaseActionCount(abstractAction)
                 }
             }
         }*/
    }

    private fun increaseActionCount(action: AbstractAction) {
        if (action.attributeValuationMap == null) {
            if (actionCount.containsKey(action)) {
                actionCount[action] = actionCount[action]!! + 1
            } else {
                if (validateActionType(action.actionType, false))
                    actionCount[action] = 1
                else {
                    val a = 1
                }
            }
            val nonDataAction = AbstractAction.getOrCreateAbstractAction(
                actionType = action.actionType,
                window = window
            )
            if (actionCount.containsKey(nonDataAction) && nonDataAction != action) {
                actionCount[nonDataAction] = actionCount[nonDataAction]!! + 1
            }

        } else if (attributeValuationMaps.contains(action.attributeValuationMap)) {
            val widgetGroup = attributeValuationMaps.find { it.equals(action.attributeValuationMap) }!!
            if (widgetGroup.containsAction(action)) {
                widgetGroup.increaseActionCount(action)
                widgetGroup.exerciseCount++
            } /*else {
                widgetGroup.actionCount[action] = 1
            }*/
            val nonDataAction = AbstractAction.getOrCreateAbstractAction(
                actionType = action.actionType,
                attributeValuationMap = widgetGroup,
                window = window
            )
            if (widgetGroup.containsAction(nonDataAction) && nonDataAction != action) {
                widgetGroup.increaseActionCount(nonDataAction)
            }
        }
        if (action.isWidgetAction()) {
            val virtualAbstractState = AbstractStateManager.INSTANCE.ABSTRACT_STATES
                .find { it.window == this.window && it is VirtualAbstractState }
            if (virtualAbstractState != null) {
                if (!virtualAbstractState.attributeValuationMaps.contains(action.attributeValuationMap!!)) {
                    virtualAbstractState.attributeValuationMaps.add(action.attributeValuationMap!!)
                    virtualAbstractState.addAction(action)
                }
            }
        }
        /*  if (updateSimilarAbstractState && !action.isWidgetAction()) {
              increaseSimilarActionCount(action)
          }*/
    }

    fun increaseSimilarActionCount(action: AbstractAction) {
        AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filter { it.window == this.window && it != this }
            .forEach {
                if (it.getAvailableActions().contains(action)) {
                    it.increaseActionCount(action)
                }
            }
    }

    fun removeAction(action: AbstractAction) {
        inputMappings.remove(action)
    }

    fun getActionCount(action: AbstractAction): Int {
        if (action.attributeValuationMap == null) {
            return actionCount[action] ?: -1
        }
        return action.attributeValuationMap.getActionCount(action)
    }

    fun getActionCountMap(): Map<AbstractAction, Int> {
        val result = HashMap<AbstractAction, Int>()
        result.putAll(actionCount)
        attributeValuationMaps.map { it.getAvailableActionsWithExercisingCount() }.forEach {
            result.putAll(it)
        }
        return result
    }

    fun isRequireRandomExploration(): Boolean {
        if ((this.window is Dialog
                    /* && !WindowManager.instance.updatedModelWindows.filter { it is OutOfApp }.map { it.classType }.contains(this.activity)*/)
            || this.isOpeningMenus
            || this.window.classType.contains("ResolverActivity")
            || this.window.classType.contains("com.android.internal.app.ChooserActivity")
        )
            return true
        return false
    }

    fun computeScore(autautMF: org.atua.modelFeatures.ATUAMF): Double {
        var localScore = 0.0
        val unexploredActions = getUnExercisedActions(null, autautMF).filterNot { it.attributeValuationMap == null }
        val windowWidgetFrequency = AbstractStateManager.INSTANCE.attrValSetsFrequency[this.window]!!
        unexploredActions.forEach {
            val actionScore = it.getScore()
            if (windowWidgetFrequency.containsKey(it.attributeValuationMap!!))
                localScore += (actionScore / windowWidgetFrequency[it.attributeValuationMap!!]!!.toDouble())
            else
                localScore += actionScore
        }
        localScore += this.getActionCountMap().map { it.value }.sum()
        this.guiStates.forEach {
            localScore += autautMF.actionCount.getUnexploredWidget2(it).size
        }
        /* actions.forEach {action ->
             autautMF.abstractTransitionGraph.edges(this).filter { edge->
                     edge.label.abstractAction == action
                             && edge.source != edge.destination
                             && edge.destination?.data !is VirtualAbstractState
                 }.forEach { edge ->
                     val dest = edge.destination?.data
                     if (dest != null) {
                         val widgetGroupFrequences = AbstractStateManager.instance.widgetGroupFrequency[dest.window]!!
                         val potentialActions = dest.getUnExercisedActions(null).filterNot { it.widgetGroup == null }
                         potentialActions.forEach {
                             if (widgetGroupFrequences.containsKey(it.widgetGroup))
                                 potentialActionCount += (1/widgetGroupFrequences[it.widgetGroup]!!.toDouble())
                             else
                                 potentialActionCount += 1
                         }
                         potentialActionCount += potentialActions.size
                     }
                 }
         }*/
        return localScore
    }

    fun extractGeneralAVMs(): Set<Map<AttributeType, String>> {
        val result = java.util.HashSet<Map<AttributeType, String>>()
        attributeValuationMaps.forEach {
            val attributes = it.localAttributes
            val lv1Attributes = java.util.HashMap<AttributeType, String>()
            attributes.forEach {
                if (it.key != AttributeType.childrenStructure
                    && it.key != AttributeType.checked
                    && it.key != AttributeType.contentDesc
                    && it.key != AttributeType.scrollable
                    && it.key != AttributeType.scrollDirection
                    && it.key != AttributeType.text
                    && it.key != AttributeType.siblingsInfo
                    && it.key != AttributeType.isLeaf
                    && it.key != AttributeType.childrenText
                    && it.key != AttributeType.xpath
                ) {
                    lv1Attributes.put(it.key, it.value)
                }
            }
            result.add(lv1Attributes)
        }
        return result
    }

    fun isSimlarAbstractState(
        lv1Attributes1: Set<Map<AttributeType, String>>,
        threshold: Double
    ): Boolean {
        var isSimilar1 = false
        var diff = 0
        val lv1Attributes = this.extractGeneralAVMs()
        diff += lv1Attributes.filter { !lv1Attributes1.contains(it) }.size
        diff += lv1Attributes1.filter { !lv1Attributes.contains(it) }.size
        if (diff * 1.0 / (lv1Attributes1.size + lv1Attributes.size) <= (1 - threshold)) {
            isSimilar1 = true
        } else {
            isSimilar1 = false
        }
        return isSimilar1
    }

    fun isSimlarAbstractState(
        comparedAppState: AbstractState,
        threshold: Double
    ): Boolean {
        if (this == comparedAppState)
            return true
        if (this.window != comparedAppState.window)
            return false
        if (this.hashCode == comparedAppState.hashCode)
            return true
        var isSimilar1 = false
        var diff = 0
        val lv1Attributes1 = this.extractGeneralAVMs()
        val lv1Attributes2 = comparedAppState.extractGeneralAVMs()
        diff += lv1Attributes1.filter { !lv1Attributes2.contains(it) }.size
        diff += lv1Attributes2.filter { !lv1Attributes1.contains(it) }.size
        if (diff * 1.0 / (lv1Attributes1.size + lv1Attributes2.size) <= (1 - threshold)) {
            isSimilar1 = true
        } else {
            isSimilar1 = false
        }
        return isSimilar1
    }

    fun similarScore(comparedAppState: AbstractState): Double {
        if (this == comparedAppState)
            return 1.0
        /*if (this.window != comparedAppState.window)
            return 0.0*/
        if (this.hashCode == comparedAppState.hashCode)
            return 1.0
        var diff = 0
        val lv1Attributes1 = this.extractGeneralAVMs()
        val lv1Attributes2 = comparedAppState.extractGeneralAVMs()
        diff += lv1Attributes1.filter { !lv1Attributes2.contains(it) }.size
        diff += lv1Attributes2.filter { !lv1Attributes1.contains(it) }.size
        return 1.0 - diff * 1.0 / (lv1Attributes1.size + lv1Attributes2.size)

    }

    /**
     * write csv
     * uuid -> AbstractState_[uuid]
     */
    open fun dump(parentDirectory: Path) {
        val dumpedAttributeValuationSet = ArrayList<String>()
        File(parentDirectory.resolve("AbstractState_" + abstractStateId.toString() + ".csv").toUri()).bufferedWriter()
            .use { all ->
                val header = header()
                all.write(header)
                attributeValuationMaps.forEach {
                    if (!dumpedAttributeValuationSet.contains(it.avmId)) {
                        all.newLine()
                        it.dump(all, dumpedAttributeValuationSet, this)
                    }
                }
            }
    }

    fun header(): String {
        return "AttributeValuationSetID;parentAVMID;${localAttributesHeader()};cardinality;captured;wtgWidgetMapping;hashcode"
    }

    private fun localAttributesHeader(): String {
        var result = ""
        AttributeType.values().toSortedSet().forEach {
            result += it.toString()
            result += ";"
        }
        result = result.substring(0, result.length - 1)
        return result
    }

    fun belongToAUT(): Boolean {
        return !this.isHomeScreen && !this.isOutOfApplication && !this.isRequestRuntimePermissionDialogBox && !this.isAppHasStoppedDialogBox
    }

    fun validateInput(input: Input): Boolean {
        val actionType = input.convertToExplorationActionName()
        return validateActionType(actionType, input.widget != null)
    }

    private fun validateActionType(actionType: AbstractActionType, isWigetAction: Boolean): Boolean {
        if (actionType == AbstractActionType.FAKE_ACTION) {
            return false
        }
        if (!isWigetAction) {
            if (actionType == AbstractActionType.CLOSE_KEYBOARD) {
                return this.isOpeningKeyboard
            }
            if (actionType == AbstractActionType.RANDOM_KEYBOARD) {
                return this.isOpeningKeyboard
            }
        }
        return true
    }

    fun getAvailableInputs(): List<Input> {
        return inputMappings.values.flatten().distinct()
    }

    fun removeInputAssociatedAbstractAction(abstractAction: AbstractAction) {
        inputMappings.remove(abstractAction)
        EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions.remove(abstractAction)
    }

    fun removeAVMAndRecomputeHashCode(avm: AttributeValuationMap) {
        attributeValuationMaps.remove(avm)
        avmCardinalities.remove(avm)
        updateHashCode()
    }

    fun containsActionCount(abstractAction: AbstractAction): Boolean {
        if (abstractAction.isWidgetAction())
            return abstractAction.attributeValuationMap!!.containsAction(abstractAction)
        return actionCount.containsKey(abstractAction)
    }

    companion object {
        val abstractStateIdByWindow = HashMap<Window, HashSet<UUID>>()
        fun computeAbstractStateHashCode(
            attributeValuationMaps: List<AttributeValuationMap>,
            avmCardinality: Map<AttributeValuationMap, Cardinality>,
            window: Window,
            rotation: org.atua.modelFeatures.Rotation
        ): Int {
            return attributeValuationMaps.sortedBy { it.hashCode }.fold(emptyUUID) { id, avs ->
                /*// e.g. keyboard elements are ignored for uid computation within [addRelevantId]
                // however different selectable auto-completion proposes are only 'rendered'
                // such that we have to include the img id (part of configId) to ensure different state configuration id's if these are different*/

                //ConcreteId(addRelevantId(id, widget), configId + widget.uid + widget.id.configId)
                id + avs.hashCode.toUUID() + avmCardinality.get(avs)!!.ordinal.toUUID()
            }.plus(listOf<String>(rotation.toString(), window.classType).joinToString("<;>").toUUID()).hashCode()
        }

        internal operator fun UUID.plus(uuid: UUID?): UUID {
            return if (uuid == null) this
            else UUID(
                this.mostSignificantBits + uuid.mostSignificantBits,
                this.leastSignificantBits + uuid.mostSignificantBits
            )
        }

    }
}

enum class InternetStatus {
    Enable,
    Disable,
    Undefined
}

class VirtualAbstractState(
    activity: String,
    staticNode: Window,
    isHomeScreen: Boolean = false
) : AbstractState(
    activity = activity,
    window = staticNode,
    avmCardinalities = HashMap(),
    rotation = org.atua.modelFeatures.Rotation.PORTRAIT,
    isHomeScreen = isHomeScreen,
    isOutOfApplication = (staticNode is OutOfApp),
    isOpeningMenus = false
)

/*
class LauncherAbstractState() : AbstractState(activity = "", window = Launcher.getOrCreateNode(), rotation = org.atua.modelFeatures.Rotation.PORTRAIT,avmCardinalities = HashMap())*/
