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

import org.atua.calm.ModelBackwardAdapter
import org.atua.calm.ewtgdiff.EWTGDiff
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.reducer.AbstractionFunction2
import org.atua.modelFeatures.dstg.reducer.StateReducer
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.WindowTransition
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.ContextMenu
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.DialogType
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.inputRepo.textInput.TextInput
import org.droidmate.deviceInterface.exploration.Rectangle
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.*
import kotlin.collections.ArrayList
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class AbstractStateManager() {
    val goBackAbstractActions = HashSet<AbstractAction>()
    val unreachableAbstractState = HashSet<AbstractState>()
    val ABSTRACT_STATES: ArrayList<AbstractState> = ArrayList()
    val launchStates: HashMap<LAUNCH_STATE, State<*>> = HashMap()
    lateinit var appResetState: AbstractState
    lateinit var atuaMF: org.atua.modelFeatures.ATUAMF
    lateinit var appName: String
    val attrValSetsFrequency = HashMap<Window, HashMap<AttributeValuationMap, Int>>()
    val ignoreImplicitDerivedTransition = HashSet<Triple<Window, AbstractAction, Window>>()

    fun init(regressionTestingMF: org.atua.modelFeatures.ATUAMF, appPackageName: String) {
        this.atuaMF = regressionTestingMF
        this.appName = appPackageName

        //create initial abstract state (after App reset)
        appResetState = createVirtualAbstractState(
            window = Launcher.getOrCreateNode(),
            activity = null,
            isHomeScreen = true)

        regressionTestingMF.dstg = DSTG()


    }

    fun initVirtualAbstractStates() {
        WindowManager.instance.updatedModelWindows.filter { it !is FakeWindow }.forEach {
            val virtualAbstractState = createVirtualAbstractState(
                activity= it.classType,
                window=it,
                isHomeScreen= it is Launcher)
        }
    }

    fun initAbstractInteractionsForVirtualAbstractStates() {
        ABSTRACT_STATES.filter { it is VirtualAbstractState }.forEach {
            initAbstractInteractions(it, null)
        }
    }

    fun createVirtualAbstractState(window: Window, activity: String?, isHomeScreen: Boolean = false): AbstractState {
        val virtualAbstractState1 = getVirtualAbstractState(window)
        if (virtualAbstractState1 != null)
            return virtualAbstractState1
        val activity1 = if (activity != null)
            activity
        else
            window.classType
        val virtualAbstractState = VirtualAbstractState(activity1, window, isHomeScreen)
        ABSTRACT_STATES.add(virtualAbstractState)
        initAbstractInteractions(virtualAbstractState, null)
        updateLaunchAndResetAbstractTransitions(virtualAbstractState)
        return virtualAbstractState
    }

    val backwardEquivalences = HashMap<AbstractState, AbstractState>()
    fun verifyBackwardEquivalent(observedState: AbstractState, expectedState: AbstractState) {
        val matchedAVMs = ArrayList<AttributeValuationMap>()
        for (attributeValuationMap1 in observedState.attributeValuationMaps) {
            for (attributeValuationMap2 in expectedState.attributeValuationMaps) {
                if (attributeValuationMap1.hashCode == attributeValuationMap2.hashCode) {
                    matchedAVMs.add(attributeValuationMap1)
                }
            }
        }
        val addedAVMs = ArrayList<AttributeValuationMap>()
        val unmatchedAVMs = ArrayList<AttributeValuationMap>()
        for (mutableEntry in observedState.EWTGWidgetMapping) {
            val avm = mutableEntry.key
            val ewtgWidget = mutableEntry.value
            if (matchedAVMs.contains(avm)) {
                continue
            }
            if (EWTGDiff.instance.getAddedWidgets().contains(ewtgWidget)) {
                addedAVMs.add(avm)
            } else {
                unmatchedAVMs.add(avm)
            }
        }
        if (unmatchedAVMs.isEmpty()) {
            backwardEquivalences.put(observedState, expectedState)
        }
    }

    fun getOrCreateNewAbstractState(
        guiState: State<*>,
        i_activity: String,
        rotation: org.atua.modelFeatures.Rotation,
        window: Window?,
        forcedCreateNew: Boolean = false
    ): AbstractState {
        if (!forcedCreateNew) {
            val exisitingAbstractState = getAbstractState(guiState)
            if (exisitingAbstractState != null) {
//                log.info("Revisited abstract state.")
                return exisitingAbstractState
            }
        }
        var abstractState: AbstractState? = null
        var activity = i_activity
        /*var internetStatus = when (internet) {
            true -> InternetStatus.Enable
            false -> InternetStatus.Disable
        }*/
        if (guiState.isHomeScreen) {
            log.info("Homescreen state.")
            var homeState = ABSTRACT_STATES.find { it.isHomeScreen }
            if (homeState != null) {
                abstractState = homeState
                if (!homeState.guiStates.contains(guiState)) {
                    mapGuiStateToAbstractState(homeState, guiState)
                }
            } else {
                abstractState = createVirtualAbstractState(
                    Launcher.getOrCreateNode(),activity,guiState.isHomeScreen)
                mapGuiStateToAbstractState(abstractState, guiState)
            }
        } /*else if (activity.isBlank() || guiState.isRequestRuntimePermissionDialogBox) {
            var outOfAppState = ABSTRACT_STATES.find { it.isOutOfApplication && it.activity == activity }
            if (outOfAppState != null) {
                abstractState = outOfAppState
                if (!outOfAppState.guiStates.contains(guiState)) {
                    outOfAppState.guiStates.add(guiState)
                }
            } else {
                outOfAppState = AbstractState(activity = activity,
                        isOutOfApplication = true,
                        window = WTGOutScopeNode.getOrCreateNode(activity),
                        rotation = rotation,
                        internet = internetStatus)
                if (outOfAppState.window.activityClass.isBlank()) {
                    outOfAppState.window.activityClass = activity
                }
                outOfAppState.guiStates.add(guiState)
                ABSTRACT_STATES.add(outOfAppState)
                abstractState = outOfAppState
            }
        }*/ else if (guiState.isAppHasStoppedDialogBox) {
            log.info("App was crash. This is Stopped Dialog Box")
            var stopState = ABSTRACT_STATES.find {
                it.isAppHasStoppedDialogBox && it.rotation == rotation
            }
            if (stopState != null) {
                abstractState = stopState
                if (!stopState.guiStates.contains(guiState)) {
                    mapGuiStateToAbstractState(abstractState, guiState)
                }
            } else {
                log.info("No Stopped Dialog Box encountered yet. Deriving new Abstract state.")
                stopState = AbstractState(
                    activity = activity,
                    isAppHasStoppedDialogBox = true,
                    window = OutOfApp.getOrCreateNode(OutOfApp.getNodeId(), activity, false),
                    avmCardinalities = HashMap(),
                    rotation = rotation
                )
                mapGuiStateToAbstractState(stopState, guiState)
                ABSTRACT_STATES.add(stopState)
                abstractState = stopState
            }
        } else {
            //log.info("Activity: $activity")
            var time1: Long = 0
            var time2: Long = 0
            var time3: Long = 0
            var time4: Long = 0
            measureNanoTime {
                val isRequestRuntimeDialogBox = guiState.isRequestRuntimePermissionDialogBox
                val isOpeningKeyboard = guiState.widgets.any { it.isKeyboard }
                val guiWidget_ewtgWidgets = HashMap<Widget, EWTGWidget>()
//                log.info("Retrieving current window...")
                val matchedWindow = if (window == null) {
                    val similarAbstractStates = ABSTRACT_STATES.filter { it.guiStates.any { it.uid == guiState.uid } }
                    if (similarAbstractStates.isEmpty() || similarAbstractStates.groupBy { it.window }.size > 1) {
                        //log.info("Matching window")
                        matchWindow(guiState, activity, rotation, guiWidget_ewtgWidgets)
                    } else {
                        similarAbstractStates.first().window
                    }
                } else {
                    window
                }
//                log.info("Retrieving current window...DONE")
                val guiTreeRectangle = Helper.computeGuiTreeDimension(guiState)
                var isOptionsMenu = if (!Helper.isDialog(rotation, guiTreeRectangle, guiState, atuaMF))
                    Helper.isOptionsMenuLayout(guiState)
                else
                    false
                /*val isOptionsMenu2 = Helper.isOptionsMenuLayout(guiState)
                if (isOptionsMenu != isOptionsMenu2) {
                    log.debug("Check")
                }*/
//                log.info("Matching GUIWidgets with EWTGWidgets...")
                Helper.matchingGUIWidgetWithEWTGWidgets(
                    guiWidget_ewtgWidgets,
                    guiState,
                    matchedWindow,
                    isOptionsMenu,
                    appName
                )
//                log.info("Matching GUIWidgets with EWTGWidgets...DONE")
                var widget_AvmHashMap = HashMap<Widget, AttributeValuationMap>()
                //log.info("State reducing.")
//                log.info("Deriving abstract state...")
                measureNanoTime {
                    widget_AvmHashMap =
                        StateReducer.reduce(guiState, matchedWindow, rotation, guiTreeRectangle, isOptionsMenu, atuaMF)
                }.let {
                    time1 = it
                }
                val avmCardinalitis = HashMap<AttributeValuationMap, Cardinality>()
                widget_AvmHashMap.values.groupBy { it }.forEach { t, u ->
                    if (u.size == 1)
                        avmCardinalitis.put(t, Cardinality.ONE)
                    else
                        avmCardinalitis.put(t, Cardinality.MANY)
                }
                TextInput.saveSpecificTextInputData(guiState)
                var derivedAVMs = widget_AvmHashMap.map { it.value }.distinct()
                // log.info("Find Abstract states.")
                val matchingTestState = findAbstractState(
                    ABSTRACT_STATES,
                    derivedAVMs,
                    avmCardinalitis,
                    matchedWindow,
                    rotation,
                    isOpeningKeyboard
                )
                if (matchingTestState != null) {
//                    log.info("Revisited abstract state.")
                    if (matchingTestState.guiStates.isEmpty()) {
                        // This is because of the rebuild model process
                        matchingTestState.countAVMFrequency()
                    }
                    mapGuiStateToAbstractState(matchingTestState, guiState)
//                    log.info("Deriving abstract state...DONE.")
                    return matchingTestState
                }
                /*     var staticMapping = Pair<Window, HashMap<AttributeValuationMap, EWTGWidget>>(first = Launcher.instance!!, second = HashMap())
                     measureNanoTime {
                         staticMapping = matchWindowAndWidgets(widget_AvmHashMap, guiState, activity, rotation,window)
                     }.let {
                         time3 = it
                     }*/
//                log.info("Matching AVMs with EWTG Widgets...")
                var avm_ewtgWidgetsHashMap =
                    matchingAvmWithEWTGWidgets(guiState, widget_AvmHashMap, guiWidget_ewtgWidgets)
//                log.info("Matching AVMs with EWTG Widgets...DONE.")
                var refinementIncrease = false
                if (avm_ewtgWidgetsHashMap.any { it.value.size > 1 }) {
                    log.warn("At least one AVM is matched to more than one ewtgWidgets")
                }
                /*while (avm_ewtgWidgetsHashMap.any { it.value.size > 1 } && !forcedCreateNew) {
                    var tempRefinementIncrease = false
                    val ambigousAVMs = avm_ewtgWidgetsHashMap.filter { it.value.size > 1 }.keys
                    ambigousAVMs.forEach { avm ->
                        val relatedGUIWidgets = widget_AvmHashMap.filter { it.value == avm }.keys
                        val abstractionFunction = AbstractionFunction2.INSTANCE
                        relatedGUIWidgets.forEach { guiWidget ->
                            if (abstractionFunction.increaseReduceLevel(guiWidget, guiState, guiWidget_ewtgWidgets.get(guiWidget)!!, matchedWindow.classType, rotation, atuaMF)) {
                                refinementIncrease = true
                                tempRefinementIncrease = true
                            }
                        }
                    }
                    if (tempRefinementIncrease == false)
                        break
                    widget_AvmHashMap = StateReducer.reduce(guiState, matchedWindow, rotation,guiTreeRectangle,isOptionsMenu, atuaMF)
                    avm_ewtgWidgetsHashMap = matchingAvmWithEWTGWidgets(guiState, widget_AvmHashMap, guiWidget_ewtgWidgets)
                }*/
                avmCardinalitis.clear()
                widget_AvmHashMap.values.groupBy { it }.forEach { t, u ->
                    if (u.size == 1)
                        avmCardinalitis.put(t, Cardinality.ONE)
                    else
                        avmCardinalitis.put(t, Cardinality.MANY)
                }
                val isOutOfApp = if (matchedWindow is OutOfApp)
                    true
                else
                    WindowManager.instance.updatedModelWindows.find { it.classType == activity } is OutOfApp
                val avm_ewtgWidgets = HashMap(avm_ewtgWidgetsHashMap.entries.filter { it.value.isNotEmpty() }
                    .associate { it.key to it.value.first() })
                measureNanoTime {
//                    log.info("Creating new Abstract state...")
                    abstractState = AbstractState(
                        activity = activity,
                        attributeValuationMaps = ArrayList(derivedAVMs),
                        avmCardinalities = avmCardinalitis,
                        isRequestRuntimePermissionDialogBox = isRequestRuntimeDialogBox,
                        isOpeningKeyboard = isOpeningKeyboard,
                        EWTGWidgetMapping = avm_ewtgWidgets,
                        window = matchedWindow,
                        rotation = rotation,
                        isOutOfApplication = isOutOfApp
                    )
                    if (abstractState!!.window is Dialog || abstractState!!.window is OptionsMenu || abstractState!!.window is ContextMenu) {
                        abstractState!!.hasOptionsMenu = false
                    }
                    if (isOptionsMenu) {
                        abstractState!!.isOpeningMenus = true
                    }
//                    log.info("Creating new Abstract state...DONE.")
                    ABSTRACT_STATES.add(abstractState!!)
                    mapGuiStateToAbstractState(abstractState!!, guiState)
//                    log.info("Initiating abstract transitions...")
//                    if (!forcedCreateNew)
                        initAbstractInteractions(abstractState!!, guiState)
//                    log.info("Initiating abstract transitions...DONE.")
                    /*                val ambigousWidgetGroup = staticMapping.second.filter {
                                                //In certain cases, static analysis distinguishes same resourceId widgets based on unknown criteria.
                                                !havingSameResourceId(it.value)
                                    }
                                    if (ambigousWidgetGroup.isEmpty() || ambigousWidgetGroup.isNotEmpty()) {



                                    }*/
                }.let {
                    time4 = it
                }
                /*if (refinementIncrease) {
                    rebuildModel(matchedWindow)
                }*/
            }.let {
                if (it > 10e8) {
                    log.debug("AbstractState creation took: ${it / 10e6.toDouble()} milliseconds, in which: ")
                    log.debug("State reducing took: ${time1 / 10e6.toDouble()} milliseconds,")
                    log.debug("Finding matching abstract state took: ${time2 / 10e6.toDouble()} milliseconds,")
                    log.debug("Get matching static widgets reducing took: ${time3 / 10e6.toDouble()} milliseconds.")
                    log.debug("Init Abstract Interactions took: ${time4 / 10e6.toDouble()} milliseconds.")
                }
            }


        }
        return abstractState!!
    }

    private fun matchingAvmWithEWTGWidgets(
        guiState: State<*>,
        guiWidget_AVMs: HashMap<Widget, AttributeValuationMap>,
        guiWidget_ewtgWidgets: HashMap<Widget, EWTGWidget>
    ): HashMap<AttributeValuationMap, ArrayList<EWTGWidget>> {
        val avm_ewtgWidgets = HashMap<AttributeValuationMap, ArrayList<EWTGWidget>>()
//                guiWidget_AVMs.entries.associate { it.value to it.key }
        guiWidget_AVMs.forEach { guiWidget, avm ->
            avm_ewtgWidgets.putIfAbsent(avm, ArrayList())
            val ewtgWidget = guiWidget_ewtgWidgets.get(guiWidget)
            if (ewtgWidget != null) {
                if (!avm_ewtgWidgets.get(avm)!!.contains(ewtgWidget)) {
                    avm_ewtgWidgets.get(avm)!!.add(ewtgWidget)
                }
            }
        }
        /*avm_guiWidgets.forEach {
            val ewtgWidget = guiWidget_ewtgWidgets.get(it.value)
            if (ewtgWidget!=null)
                result.put(it.key,ewtgWidget)
        }*/
        return avm_ewtgWidgets
    }

    private fun mapGuiStateToAbstractState(matchingTestState: AbstractState, guiState: State<*>) {
        if (!matchingTestState.guiStates.contains(guiState))
            matchingTestState.guiStates.add(guiState)
        guiState_AbstractState_Map.put(guiState.stateId, matchingTestState)
    }

    enum class LAUNCH_STATE {
        NONE,
        NORMAL_LAUNCH,
        RESET_LAUNCH
    }

    private fun findAbstractState(
        abstractStateList: List<AbstractState>,
        guiReducedAttributeValuationMap: List<AttributeValuationMap>,
        avmCardinalites: Map<AttributeValuationMap, Cardinality>,
        window: Window,
        rotation: org.atua.modelFeatures.Rotation,
        isOpeningKeyboard: Boolean
    ): AbstractState? {
        val predictedAbstractStateHashcode = AbstractState.computeAbstractStateHashCode(
            guiReducedAttributeValuationMap,
            avmCardinalites,
            window,
            rotation
        )
        var result: AbstractState? = null
        for (abstractState in abstractStateList.filter {
            it !is VirtualAbstractState
                    && it.window == window
                    && it.rotation == rotation
                    && it.isOpeningKeyboard == isOpeningKeyboard
        }) {
            if (abstractState.hashCode == predictedAbstractStateHashcode) {
                return abstractState
            }
            val matchedAVMs = HashMap<AttributeValuationMap, AttributeValuationMap>()

        }
        return result
    }

    private fun havingSameResourceId(eWTGWidget: EWTGWidget): Boolean {
        var resourceId: String = eWTGWidget.resourceIdName
        if (!eWTGWidget.resourceIdName.equals(resourceId)) {
            return false
        }
        return true
    }

    fun initAbstractInteractions(abstractState: AbstractState, guiState: State<*>? = null) {
        abstractState.initAction()

        val inputs = abstractState.window.inputs
        inputs.forEach { input ->
            if (input.widget != null)
                initWidgetInputForAbstractState(abstractState, input)
            else
                initWindowInputForAbstractState(abstractState, input)
        }
        abstractState.getAvailableActions(guiState).forEach {
            if (!abstractState.isAbstractActionMappedWithInputs(it)) {
                if (!it.isLaunchOrReset() && !it.isActionQueue()) {
                    Input.getOrCreateInputFromAbstractAction(abstractState, it,abstractState.modelVersion)
                }
            }
        }
        //create implicit widget interactions from VirtualAbstractState
        if (abstractState is VirtualAbstractState) {
            return
        }
        val virtualAbstractStates =
            ABSTRACT_STATES.filter { it is VirtualAbstractState && it.window == abstractState.window }
        if (virtualAbstractStates.isEmpty()) {
            return
        }
        val virtualAbstractState = virtualAbstractStates.first()

        createAbstractActionsFromVirtualAbstractState(abstractState, guiState)

//        createAbstractTransitionsFromVirtualAbstractState(abstractState)
        updateLaunchAndResetAbstractTransitions(abstractState)
        createAbstractTransitionsFromWTG(abstractState)

        createAbstractTransitionsForImplicitIntents(abstractState)
    }

    private fun createAbstractTransitionsFromVirtualAbstractState(abstractState: AbstractState) {
        val similarAbstractStates = getSimilarAbstractStates(abstractState)
        val notSoDifferentAbstractStates =
            getSlightlyDifferentAbstractStates(abstractState, similarAbstractStates)
        val explicitTransitions = notSoDifferentAbstractStates.map { similarAbstractState ->
            similarAbstractState.abstractTransitions.filter {
                //we will not process any self edge
                (it.interactions.isNotEmpty() || it.modelVersion == ModelVersion.BASE)
                        && it.activated == true
                        && consideredForImplicitAbstractStateAction(it.abstractAction)
            }
        }.flatten().sortedByDescending { it.interactions.map { it.endTimestamp }.maxOrNull() ?: LocalDateTime.MIN }
        for (transition in explicitTransitions) {
            if (consideredForImplicitAbstractStateAction(transition.abstractAction)
            ) {
                continue
            }
            var ignoreTransition = false
            val retainingEWTGWidgets = transition.source.EWTGWidgetMapping
                .map { it.value }.intersect(
                    transition.dest.EWTGWidgetMapping.map { it.value }).distinct()
            if (ignoreImplicitDerivedTransition.any {
                    it.first == transition.source.window
                            && it.second == transition.abstractAction && it.third == transition.dest.window
                })
                ignoreTransition = true
            if (ignoreTransition)
                continue
            val dest = if (transition.dest == transition.source)
                abstractState
            else
                transition.dest
            val userInputs = transition.userInputs
            // initAbstractActionCount
            val existingAction = abstractState.getAvailableActions().find {
                it == transition.abstractAction
            }
            if (existingAction != null) {
                val exisitingImplicitTransitions = abstractState.abstractTransitions.filter {
                    it.abstractAction == existingAction
                            /*&& it.prevWindow == abstractTransition.prevWindow*/
                            && (
                            it.dependentAbstractStates.intersect(transition.dependentAbstractStates).isNotEmpty()
                                    || it.guardEnabled == false
                                    || it.dependentAbstractStates.isEmpty()
                            )
                            && (!transition.abstractAction.isItemAction() ||
                            it.data == transition.data)
                            /*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                            || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                            && it.isImplicit
                            && it.dest == transition.dest
                }
                if (exisitingImplicitTransitions.isNotEmpty())
                    continue
                val existingAbstractTransition = abstractState.abstractTransitions.find {
                    it.abstractAction == existingAction
                            /*&& it.prevWindow == abstractTransition.prevWindow*/
                            && it.dest == dest
                    /*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                    || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                }
                val abstractInteraction = if (existingAbstractTransition == null) {
                    AbstractTransition(
                        abstractAction = existingAction,
                        isImplicit = true,
                        /*prevWindow = transition.prevWindow,*/
                        data = transition.data,
                        source = abstractState,
                        dest = dest
                    ).also {
                        atuaMF.dstg.add(abstractState, it.dest, it)
                    }
                } else {
                    existingAbstractTransition
                }
                abstractInteraction.userInputs.addAll(userInputs)
                abstractInteraction.dependentAbstractStates.addAll(transition.dependentAbstractStates)
                abstractInteraction.guardEnabled = transition.guardEnabled
                abstractInteraction.guaranteedRetainedAVMs.clear()
                val guaranteedAVMs =
                    transition.guaranteedRetainedAVMs.filter { abstractState.attributeValuationMaps.contains(it) }
                abstractInteraction.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
                abstractInteraction.guaranteedNewAVMs.addAll(transition.guaranteedNewAVMs)
            }
        }
        notSoDifferentAbstractStates.forEach { similarAbstractState ->
            val explicitTransitions = similarAbstractState.abstractTransitions.filter {
                //we will not process any self edge
                it.interactions.isNotEmpty()
                        && it.activated == true
                        && consideredForImplicitAbstractStateAction(it.abstractAction)
            }
            for (transition in explicitTransitions) {
                if (transition.abstractAction.actionType == AbstractActionType.PRESS_BACK
                    || transition.abstractAction.isLaunchOrReset()
                ) {
                    continue
                }
                var ignoreTransition = false
                val retainingEWTGWidgets = transition.source.EWTGWidgetMapping
                    .map { it.value }.intersect(
                        transition.dest.EWTGWidgetMapping.map { it.value }).distinct()

                if (ignoreTransition)
                    continue
                val dest = if (transition.dest == transition.source)
                    abstractState
                else
                    transition.dest
                val userInputs = transition.userInputs
                // initAbstractActionCount
                val existingAction = abstractState.getAvailableActions().find {
                    it == transition.abstractAction
                }
                if (existingAction != null) {
                    val existingAbstractTransition = abstractState.abstractTransitions.find {
                        it.abstractAction == existingAction
                                /*&& it.prevWindow == abstractTransition.prevWindow*/
                                && it.dest == dest
                        /*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                        || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                    }
                    val abstractInteraction = if (existingAbstractTransition == null) {
                        AbstractTransition(
                            abstractAction = existingAction,
                            isImplicit = true,
                            /*prevWindow = transition.prevWindow,*/
                            data = transition.data,
                            source = abstractState,
                            dest = dest
                        ).also {
                            atuaMF.dstg.add(abstractState, it.dest, it)
                        }
                    } else {
                        existingAbstractTransition
                    }
                    abstractInteraction.userInputs.addAll(userInputs)
                    abstractInteraction.dependentAbstractStates.addAll(transition.dependentAbstractStates)
                    abstractInteraction.guardEnabled = transition.guardEnabled
                    abstractInteraction.guaranteedRetainedAVMs.clear()
                    val guaranteedAVMs =
                        transition.guaranteedRetainedAVMs.filter { abstractState.attributeValuationMaps.contains(it) }
                    abstractInteraction.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
                    abstractInteraction.guaranteedNewAVMs.addAll(transition.guaranteedNewAVMs)
                }
            }
        }

    }

    private fun createAbstractActionsFromVirtualAbstractState(abstractState: AbstractState, guiState: State<*>?) {
        val similarAbstractStates = getSimilarAbstractStates(abstractState)
        similarAbstractStates.forEach { similarAbstractState ->
            similarAbstractState.getAvailableActions(guiState).forEach { abstractAction ->
//                val isTarget = similarAbstractState.targetActions.contains(abstractAction)
                var existingAction = abstractState.getAvailableActions(guiState).find {
                    it == abstractAction
                }
                if (existingAction == null) {
                    if (abstractAction.attributeValuationMap != null) {
                        val avm =
                            abstractState.attributeValuationMaps.find { it.equals(abstractAction.attributeValuationMap) }
                        if (avm != null) {
                            existingAction = AbstractAction.getOrCreateAbstractAction(
                                actionType = abstractAction.actionType,
                                attributeValuationMap = avm,
                                extra = abstractAction.extra,
                                window = abstractState.window
                            )
                        } /*else if (guiState != null) {
                            val guiWidget = abstractAction.attributeValuationMap.getGUIWidgets(guiState).firstOrNull()
                            // guiState.widgets.find { virtualAbstractAction.widgetGroup.isAbstractRepresentationOf(it,guiState) }
                            if (guiWidget != null) {
                                val newAttributePath = AttributeValuationMap.allWidgetAVMHashMap[abstractState.window]!!.get(guiWidget)!!
                                val newWidgetGroup = newAttributePath
                                existingAction = AbstractAction(actionType = abstractAction.actionType,
                                    attributeValuationMap = newWidgetGroup,
                                    extra = abstractAction.extra)
                            }
                        }*/
                    } else {
                        existingAction = AbstractAction.getOrCreateAbstractAction(
                            actionType = abstractAction.actionType,
                            attributeValuationMap = null,
                            extra = abstractAction.extra,
                            window = abstractState.window
                        )
                    }
                }
                if (existingAction != null) {
                    /*if (isTarget) {
                        abstractState.targetActions.add(existingAction)
                    }*/
                    val oldActionCount = abstractState.getActionCount(existingAction)
                    if (!abstractState.containsActionCount(existingAction)) {
                        val actionCount = similarAbstractState.getActionCount(abstractAction)
                        abstractState.setActionCount(existingAction, actionCount)
                    }
                }
            }
        }

    }

    private fun createAbstractTransitionsForImplicitIntents(abstractState: AbstractState) {
        WindowManager.instance.intentFilter.forEach { window, intentFilters ->
            val destVirtualAbstractState = ABSTRACT_STATES.find { it.window == window && it is VirtualAbstractState }
            if (destVirtualAbstractState != null) {
                intentFilters.forEach {
                    val abstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.SEND_INTENT,
                        extra = it,
                        window = abstractState.window
                    )
                    val abstractInteraction = AbstractTransition(
                        abstractAction = abstractAction,
                        isImplicit = true,
                        /*prevWindow = null,*/
                        data = null,
                        source = abstractState,
                        dest = destVirtualAbstractState,
                        fromWTG = true
                    )
                    atuaMF.dstg.add(abstractState, destVirtualAbstractState, abstractInteraction)

                }
            }
        }
    }

    private fun createAbstractTransitionsFromWTG(abstractState: AbstractState) {
        //create implicit widget interactions from static Node
        val nonTrivialWidgetWindowsTransitions = atuaMF.wtg.edges(abstractState.window)
            .filter { it.label.input.widget != null }
            .filterNot { it.source.data == it.destination?.data }

        /*    nonTrivialWindowTransitions
                    .forEach { windowTransition ->
                        val abstractActions = abstractState.inputMappings.filter { it.value.contains(windowTransition.label.input) }.map { it.key }
                        val destWindow = windowTransition.destination!!.data
                        val destAbstractState = ABSTRACT_STATES.find { it.window == destWindow && it is VirtualAbstractState }
                        if (destAbstractState != null) {
                            abstractActions.forEach { abstractAction ->
                                createAbstractTransitionFromWindowTransition(abstractState, abstractAction, windowTransition, destAbstractState)
                            }
                        }
                    }*/

        nonTrivialWidgetWindowsTransitions
            .forEach { windowTransition ->
                val destWindow = windowTransition.destination!!.data
                val destAbstractState = ABSTRACT_STATES.find { it.window == destWindow && it is VirtualAbstractState }
                if (destAbstractState != null) {
                    val abstractActions =
                        abstractState.getAbstractActionsWithSpecificInputs(windowTransition.label.input)
                    abstractActions.forEach { abstractAction ->
                        createAbstractTransitionFromWindowTransition(
                            abstractState,
                            abstractAction,
                            windowTransition,
                            destAbstractState
                        )
                    }
                }
            }
    }

    private fun createAbstractTransitionFromWindowTransition(
        abstractState: AbstractState,
        abstractAction: AbstractAction,
        windowTransition: Edge<Window, WindowTransition>,
        destAbstractState: AbstractState
    ) {
        val abstractEdge = atuaMF.dstg.edges(abstractState).find {
            it.label.abstractAction == abstractAction
                    && it.label.data == windowTransition.label.input.data
                    /*&& it.label.prevWindow == null*/
                    && it.label.dest == destAbstractState
        }

        var abstractTransition: AbstractTransition
        if (abstractEdge == null) {
            abstractTransition = AbstractTransition(
                abstractAction = abstractAction,
                isImplicit = true,
                /*prevWindow = null,*/
                data = windowTransition.label.input.data,
                fromWTG = true,
                source = abstractState,
                dest = destAbstractState
            )
            windowTransition.label.input.modifiedMethods.forEach {
                abstractTransition.modifiedMethods.put(it.key, false)
            }
            abstractState.associateAbstractActionWithInputs(
                abstractTransition.abstractAction,
                windowTransition.label.input
            )
            /*if (atuaMF.notFullyExercisedTargetInputs.contains(windowTransition.label.input)) {
                abstractState.targetActions.add(abstractTransition.abstractAction)
            }*/
            atuaMF.dstg.add(abstractState, destAbstractState, abstractTransition)
            abstractState.abstractTransitions.add(abstractTransition)
        }

    }

    private fun initWidgetInputForAbstractState(abstractState: AbstractState, input: Input) {
        if (input.widget == null)
            return
        val avms =
            abstractState.EWTGWidgetMapping.filter { m -> m.value == input.widget }.map { it.key }.toMutableList()
        if (avms.isEmpty() && abstractState is VirtualAbstractState) {
            //create a fake widgetGroup
            val staticWidget = input.widget
            val localAttributes = HashMap<AttributeType, String>()
            localAttributes.put(AttributeType.resourceId, staticWidget!!.resourceIdName)
            localAttributes.put(AttributeType.className, staticWidget!!.className)

            val attributePath = AttributePath(localAttributes = localAttributes, window = abstractState.window)
            val virtAVM = AttributeValuationMap(attributePath = attributePath, window = abstractState.window)
            //abstractState.addAttributeValuationSet(virtAVM)
            abstractState.EWTGWidgetMapping.put(virtAVM, input.widget!!)
            avms.add(virtAVM)
        }
        avms.forEach { avm ->
            var widgetAbstractAction = abstractState.getAvailableActions().find {
                it.actionType == input.convertToExplorationActionName()
                        && it.attributeValuationMap == avm
                        && it.extra == input.data
            }
            if (widgetAbstractAction == null) {
                if (abstractState is VirtualAbstractState) {
                    widgetAbstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = input.convertToExplorationActionName(),
                        attributeValuationMap = avm,
                        extra = input.data,
                        window = input.sourceWindow
                    )
                } else {
                    val actionName = input.convertToExplorationActionName()
                    if (actionName == AbstractActionType.ITEM_CLICK || actionName == AbstractActionType.ITEM_LONGCLICK) {
                        widgetAbstractAction = AbstractAction.getOrCreateAbstractAction(
                            actionType = input.convertToExplorationActionName(),
                            attributeValuationMap = avm,
                            extra = input.data,
                            window = input.sourceWindow
                        )
                    }
                }
            }
            if (widgetAbstractAction != null) {
                if (!abstractState.getAvailableActions().contains(widgetAbstractAction)) {
                    abstractState.addAction(widgetAbstractAction)
                }
                abstractState.associateAbstractActionWithInputs(widgetAbstractAction, input)
            }
        }
    }

    private fun initWindowInputForAbstractState(abstractState: AbstractState, input: Input) {
        var abstractAction = abstractState.getAvailableActions().find {
            it.actionType == input.convertToExplorationActionName()
                    && it.attributeValuationMap == null
                    && it.extra == input.data
        }
        if (abstractAction == null) {
            if (abstractState.validateInput(input))
                abstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = input.convertToExplorationActionName(),
                    extra = input.data,
                    window = abstractState.window
                )
        }
        if (abstractAction == null)
            return
        abstractState.setActionCount(abstractAction,0)
        abstractState.associateAbstractActionWithInputs(abstractAction, input)
    }

    fun updateLaunchAndResetAbstractTransitions(abstractState: AbstractState) {
        val launchState = launchStates[LAUNCH_STATE.NORMAL_LAUNCH]
        if (launchState == null)
            return
        val resetState = launchStates[LAUNCH_STATE.RESET_LAUNCH]
        if (resetState == null)
            return
        val launchAbstractState = getAbstractState(launchState!!)
        val resetAbstractState = getAbstractState(resetState!!)
        if (launchAbstractState == null)
            return
        if (resetAbstractState == null)
            return
//        updateOrCreateLaunchAppTransition(abstractState, launchAbstractState!!)
        updateOrCreateResetAppTransition(abstractState, resetAbstractState!!)
    }

    val guiState_AbstractState_Map = HashMap<ConcreteId, AbstractState>()
    fun getAbstractState(guiState: State<*>): AbstractState? {
        var abstractState = guiState_AbstractState_Map.get(guiState.stateId)
        if (abstractState == null) {
            abstractState = ABSTRACT_STATES.find { it.guiStates.contains(guiState) }
            if (abstractState != null) {
                return abstractState
            }
        }
        return abstractState
    }

    fun hasSameAVS(widgetGroups1: Set<AttributeValuationMap>, widgetGroups2: Set<AttributeValuationMap>): Boolean {
        if (widgetGroups1.hashCode() == widgetGroups2.hashCode())
            return true
        return false
    }

    fun matchWindow(
        guiState: State<*>,
        activity: String,
        rotation: org.atua.modelFeatures.Rotation,
        guiWidget_ewtgWidgets: HashMap<Widget, EWTGWidget>
    ): Window {
        //check if the previous state is homescreen
        var bestMatchedNode: Window? = null
        val guiTreeDimension = Helper.computeGuiTreeDimension(guiState)
        val isOpeningKeyboard = guiState.visibleTargets.any { it.isKeyboard }
        val isMenuOpen = Helper.isOptionsMenuLayout(guiState)
        var activityNode: Window? = WindowManager.instance.updatedModelWindows.find { it.classType == activity }
        if (activityNode == null) {
            val newWTGNode =
                if (guiState.widgets.any { it.packageName == atuaMF.packageName }
                    && !guiState.isRequestRuntimePermissionDialogBox) {
                    Activity.getOrCreateNode(
                        nodeId = Activity.getNodeId(),
                        classType = activity,
                        runtimeCreated = true,
                        isBaseMode = false
                    )
                } else {
                    OutOfApp.getOrCreateNode(OutOfApp.getNodeId(), activity, false)
                }
            createVirtualAbstractState(window = newWTGNode, activity = activity)
            activityNode = newWTGNode
        }
        val windowId =
            guiState.widgets.find { !it.isKeyboard }?.metaInfo?.find { it.contains("windowId") }?.split(" = ")?.get(1)
        if (windowId != null) {
            val sameWindowIdWindow =
                WindowManager.instance.updatedModelWindows.find { it.windowRuntimeIds.contains(windowId) }
            if (sameWindowIdWindow != null) {
                bestMatchedNode = sameWindowIdWindow
            }
        }
        if (bestMatchedNode == null) {
            val allPossibleNodes = ArrayList<Window>()
            //if the previous state is not homescreen
            //Get candidate nodes
            val isDialog = Helper.isDialog(rotation, guiTreeDimension, guiState, atuaMF)
            if (!isDialog) {
                bestMatchedNode = activityNode
            } else {
                if (activityNode !is OutOfApp) {
                    val allApplicationDialogClasses = WindowManager.instance.dialogClasses.filter {
                        it.key == DialogType.APPLICATION_DIALOG
                                || it.key == DialogType.DIALOG_FRAGMENT
                    }.map { it.value }.flatten()
                    val recentExecutedMethods = atuaMF!!.statementMF!!.recentExecutedMethods.map {
                        atuaMF.statementMF!!.methodInstrumentationMap.get(it)!!
                    }
                    val dialogMethods = recentExecutedMethods
                        .filter { m ->
                            allApplicationDialogClasses.any {
                                m.contains("$it: void <init>()")
                                        || m.contains("$it: void onCreate(android.os.Bundle)")
                                        || m.contains("$it: android.app.Dialog onCreateDialog(android.os.Bundle)")
                                        || m.contains("$it: android.view.View onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)")
                            }
                        }.associateWith { m -> allApplicationDialogClasses.find { m.contains(it) }!! }
                    val possibleDialogs = WindowManager.instance.allMeaningWindows
                        .filter { it is Dialog }
                        .filter {
                            dialogMethods.values.contains(it.classType)
                        }
                    if (possibleDialogs.isEmpty()) {
                        val allLibrayDialogs =
                            WindowManager.instance.dialogClasses.filter { it.key == DialogType.LIBRARY_DIALOG }
                                .map { it.value }.flatten()
                        val recentExecuteStatements = atuaMF!!.statementMF!!.recentExecutedStatements.map {
                            atuaMF.statementMF!!.statementInstrumentationMap.get(it)!!
                        }
                        val libraryDialogMethods = recentExecuteStatements
                            .filter { m -> allLibrayDialogs.any { m.contains(it) } }
                            .associateWith { m -> allLibrayDialogs.find { m.contains(it) }!! }
                        val possibleLibraryDialogs = WindowManager.instance.allMeaningWindows
                            .filter { it is Dialog }
                            .filter {
                                libraryDialogMethods.values.contains(it.classType)
                                        && !recentExecuteStatements.any { s ->
                                    s.contains("${it.classType}: void dismiss()")
                                }
                            }
                        if (possibleLibraryDialogs.isNotEmpty()) {
                            allPossibleNodes.addAll(possibleLibraryDialogs)
                        }
                        val candidateDialogs = WindowManager.instance.allMeaningWindows
                            .filter { it is Dialog && it.isRuntimeCreated && it.ownerActivitys.contains(activityNode) }
                        allPossibleNodes.addAll(candidateDialogs)

                    } else {
                        allPossibleNodes.addAll(possibleDialogs)
                    }
                } else {
                    val candidateDialogs = WindowManager.instance.allMeaningWindows
                        .filter { it is Dialog && it.isRuntimeCreated && it.ownerActivitys.contains(activityNode) }
                    allPossibleNodes.addAll(candidateDialogs)
                }
                /*val dialogNodes = ArrayList(atuaMF.wtg.getDialogs(activityNode))
                //val dialogNodes = WTGDialogNode.allNodes
                WindowManager.instance.updatedModelWindows.filter {it is Dialog && it.activityClass == activity }.forEach {
                    if (!dialogNodes.contains(it)) {
                        dialogNodes.add(it as Dialog)
                    }
                }
                allPossibleNodes.addAll(dialogNodes)*/

            }

            if (bestMatchedNode == null) {
                //Find the most similar node
                //only at least 1 widget matched is in the return result
                if (allPossibleNodes.size > 0 && !isDialog) {
                    bestMatchedNode = activityNode
                } else if (allPossibleNodes.size > 0) {
                    val matchWeights =
                        Helper.calculateMatchScoreForEachNode2(guiState, allPossibleNodes, appName, isMenuOpen)
                    //sort and get the highest ranking of the match list as best matched node
                    val sortedWeight = matchWeights.map { it.value }.sortedDescending()
                    val largestWeight = sortedWeight.first()
                    if (largestWeight != Double.NEGATIVE_INFINITY) {
                        val topMatchingNodes = matchWeights.filter { it.value == largestWeight }
                        if (topMatchingNodes.size == 1) {
                            bestMatchedNode = topMatchingNodes.entries.first().key
                        } else {
                            val sortByPercentage =
                                topMatchingNodes.toSortedMap(compareByDescending { matchWeights[it]!! / it.widgets.size.toDouble() })
                            bestMatchedNode =
                                topMatchingNodes.filter { it.value == sortByPercentage[sortByPercentage.firstKey()]!! }.entries.firstOrNull()?.key
                            if (bestMatchedNode == null) {
                                bestMatchedNode = sortByPercentage.firstKey()
                            }
                        }
                    } else {
                        val newWTGDialog = createNewDialog(
                            activity,
                            activityNode,
                            rotation,
                            guiTreeDimension,
                            isOpeningKeyboard,
                            guiState.isRequestRuntimePermissionDialogBox
                        )
                        bestMatchedNode = newWTGDialog
                    }
                } else {
                    if (!isDialog) {
                        bestMatchedNode = activityNode
                    } else {
                        val newWTGDialog = createNewDialog(
                            activity,
                            activityNode,
                            rotation,
                            guiTreeDimension,
                            isOpeningKeyboard,
                            guiState.isRequestRuntimePermissionDialogBox
                        )
                        bestMatchedNode = newWTGDialog
                    }
                }
            }
        }
        if (isDimensionEmpty(bestMatchedNode!!, rotation, isOpeningKeyboard)) {
            setDimension(bestMatchedNode, rotation, guiTreeDimension, isOpeningKeyboard)
        }
        // bestMatchedNode.activityClass = activity
        if (bestMatchedNode is Dialog) {
            bestMatchedNode.ownerActivitys.add(activityNode)
        }
        if (windowId != null)
            bestMatchedNode!!.windowRuntimeIds.add(windowId)
        return bestMatchedNode
    }

    private fun createNewDialog(
        activity: String,
        activityNode: Window,
        rotation: org.atua.modelFeatures.Rotation,
        guiTreeDimension: Rectangle,
        isOpeningKeyboard: Boolean,
        isGrantedRuntimeDialog: Boolean
    ): Dialog {
        val newId = Dialog.getNodeId()
        val newWTGDialog = Dialog.getOrCreateNode(
            newId,
            activity + newId, "", true, false, isGrantedRuntimeDialog
        )
        //autautMF.wtg.add(activityNode, newWTGDialog, FakeEvent(activityNode))
        setDimension(newWTGDialog, rotation, guiTreeDimension, isOpeningKeyboard)
        // regressionTestingMF.transitionGraph.copyNode(activityNode!!,newWTGDialog)
        createVirtualAbstractState(newWTGDialog,activity)
        newWTGDialog.ownerActivitys.add(activityNode)
        return newWTGDialog
    }

    private fun setDimension(
        bestMatchedNode: Window,
        rotation: org.atua.modelFeatures.Rotation,
        guiTreeDimension: Rectangle,
        isOpeningKeyboard: Boolean
    ) {
        if (!isOpeningKeyboard) {
            if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
                bestMatchedNode.portraitDimension = guiTreeDimension
                return
            }
            bestMatchedNode.landscapeDimension = guiTreeDimension
            return
        }
        if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
            bestMatchedNode.portraitKeyboardDimension = guiTreeDimension
            return
        }
        bestMatchedNode.landscapeKeyboardDimension = guiTreeDimension
        return
    }

    private fun isSameDimension(
        window: Window,
        guiTreeDimension: Rectangle,
        rotation: org.atua.modelFeatures.Rotation,
        isOpeningKeyboard: Boolean
    ): Boolean {
        if (!isOpeningKeyboard) {
            if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
                return window.portraitDimension == guiTreeDimension
            }
            return window.landscapeDimension == guiTreeDimension
        }
        if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
            return window.portraitKeyboardDimension == guiTreeDimension
        }
        return window.landscapeKeyboardDimension == guiTreeDimension
    }

    private fun isDimensionEmpty(
        window: Window,
        rotation: org.atua.modelFeatures.Rotation,
        isOpeningKeyboard: Boolean
    ): Boolean {
        if (!isOpeningKeyboard) {
            if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
                return window.portraitDimension.isEmpty()
            }
            return window.landscapeDimension.isEmpty()
        }
        if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
            return window.portraitKeyboardDimension.isEmpty()
        }
        return window.landscapeKeyboardDimension.isEmpty()
    }

    val REFINEMENT_MAX = 25

    val guardedTransitions = ArrayList<Pair<Window, Input>>()
    fun refineModel(
        guiInteraction: Interaction<*>,
        actionGUIState: State<*>,
        abstractTransition: AbstractTransition
    ): Int {
        val abstractionFunction = AbstractionFunction2.INSTANCE
        val actionWidget = guiInteraction.targetWidget
        if (actionWidget == null || abstractTransition.abstractAction.actionType == AbstractActionType.RANDOM_KEYBOARD) {
            return 0
        }
        val originalActionAbstractState = getAbstractState(actionGUIState)!!
        val actionEWTGWidget =
            WindowManager.instance.guiWidgetEWTGWidgetMappingByWindow.get(originalActionAbstractState.window)
                ?.get(actionWidget)
        if (actionEWTGWidget == null)
            return 0
        AbstractionFunction2.backup(atuaMF)

        var refinementGrainCount = 0

        //val attributeValuationSet = originalActionAbstractState.getAttributeValuationSet(guiInteraction.targetWidget!!,actionGUIState,atuaMF = atuaMF)!!
        val guiTreeRectangle = Helper.computeGuiTreeDimension(actionGUIState)
        var isOptionsMenu =
            if (!Helper.isDialog(originalActionAbstractState.rotation, guiTreeRectangle, actionGUIState, atuaMF))
                Helper.isOptionsMenuLayout(actionGUIState)
            else
                false
        if (AbstractionFunction2.INSTANCE.isAbandonedAbstractTransition(
                originalActionAbstractState.activity,
                abstractTransition
            )
        )
            return 0
        AbstractionFunction2.backup(atuaMF)
        var changed = false
        while (!validateModel(guiInteraction, actionGUIState)) {
            val actionAbstractState = getAbstractState(actionGUIState)!!

            val abstractTransition = actionAbstractState.abstractTransitions.find {
                it.interactions.contains(guiInteraction)
            }
            if (abstractTransition == null)
                break
            changed = true
            log.info("Increase refinement")
            if (abstractionFunction.increaseReduceLevel(
                    actionWidget,
                    actionGUIState,
                    actionEWTGWidget,
                    actionAbstractState.window.classType,
                    actionAbstractState.rotation,
                    atuaMF,
                    2,
                    false
                )
            ) {
                refinementGrainCount += 1
//                rebuildPartly(guiInteraction, actionGUIState)
                rebuildModel(originalActionAbstractState.window, actionEWTGWidget)
            } else {
                var needRefine = true
                /*val similarExplicitTransitions = ArrayList<AbstractTransition>()
                val sameWindowAbstractStates =
                    getSimilarAbstractStates(actionAbstractState, abstractTransition).filter {
                        it.attributeValuationMaps.contains(abstractTransition.abstractAction.attributeValuationMap!!)
                    }
                val similarAbstractStates =
                    getSlightlyDifferentAbstractStates(actionAbstractState, sameWindowAbstractStates)
                similarAbstractStates.add(actionAbstractState)
                similarAbstractStates.forEach {
                    val similarATs =
                        getType1SimilarAbstractTransitions(it, abstractTransition, abstractTransition.userInputs)
                    similarExplicitTransitions.addAll(similarATs)
                }
                similarExplicitTransitions.add(abstractTransition)*/
                /*similarExplicitTransitions.forEach {
                    if (it.guardEnabled == false) {
                        it.guardEnabled = true
                        needRefine = false
                    }
                }*/
                if (needRefine) {
                    if (abstractionFunction.increaseReduceLevel(
                            actionWidget,
                            actionGUIState,
                            actionEWTGWidget,
                            actionAbstractState.window.classType,
                            actionAbstractState.rotation,
                            atuaMF
                        )
                    ) {
                        refinementGrainCount += 1
                        rebuildModel(originalActionAbstractState.window, actionEWTGWidget)
                    } else {
                        if (refinementGrainCount > 0) {
                            refinementGrainCount = 0
                            AbstractionFunction2.restore(atuaMF)
                            rebuildModel(originalActionAbstractState.window, actionEWTGWidget)
                        }
                        AbstractionFunction2.INSTANCE.abandonedAbstractTransitions.add(abstractTransition)

                        val actionAbstractState = getAbstractState(actionGUIState)!!
                        val abstractTransition = actionAbstractState.abstractTransitions.find {
                            it.interactions.contains(guiInteraction)
                        }
                        if (abstractTransition != null) {
//                            AbstractionFunction2.INSTANCE.abandonedAbstractTransitions.add(abstractTransition)

                            val similarExplicitTransitions = ArrayList<AbstractTransition>()
                            val similarATs = getType2SimilarAbstractTransitions(
                                actionAbstractState,
                                abstractTransition,
                                abstractTransition.userInputs
                            )
                            similarExplicitTransitions.addAll(similarATs)
                            var solved = false
                            if (similarExplicitTransitions.isNotEmpty()) {
                                // try remove userInput

                                if (abstractTransition.abstractAction.actionType == AbstractActionType.TEXT_INSERT) {
                                    abstractTransition.data = guiInteraction.data
                                    solved = true
                                }
                                similarExplicitTransitions.forEach {
                                    /*if (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()) {
                                    it.userInputs.removeAll(abstractTransition.userInputs)
                                    solved = true
                                }*/
                                    it.activated = false
                                }
                            } else {
                                solved = true
                            }
                            if (!solved) {
                                log.debug("Non-deterministic transitions not solved by abstract state refinement.")
                            }
                        } else {
                            log.debug("Interaction lost")
                        }
                        /*similarExpliciTransitions.forEach {
                        val inputGUIStates = it.interactions.map { interaction -> interaction.prevState }
                        it.inputGUIStates.addAll(inputGUIStates)
                    }*/
                        break
                    }
                }
            }
        }
        /*  if (refinementGrainCount == 0) {
              rebuildModel(originalActionAbstractState.window, true, actionGUIState, guiInteraction)
          }*/
        //get number of Abstract Interaction
        if (changed) {
            ABSTRACT_STATES.filter {
                it.window == originalActionAbstractState.window
                        && it.guiStates.isNotEmpty()
            }.forEach {
                initAbstractInteractions(it)
            }
        }
        log.debug("Refinement grain increased count: $refinementGrainCount")
        return refinementGrainCount
    }

    private fun rebuildPartly(guiInteraction: Interaction<*>, actionGUIState: State<*>) {
        val actionAbstractState = getAbstractState(actionGUIState)!!
        val abstractEdges = ArrayList<Edge<AbstractState, AbstractTransition>>()
        val abstractEdge = atuaMF.dstg.edges(actionAbstractState).find {
            it.label.interactions.contains(guiInteraction)
                    && !it.label.isImplicit
        }!!
        abstractEdges.add(abstractEdge)
        val oldAbstractStatesByWindow = HashMap<Window, ArrayList<AbstractState>>()
        oldAbstractStatesByWindow.put(actionAbstractState.window, arrayListOf(actionAbstractState))
        recomputeAbstractStatesAndAbstractTransitions(oldAbstractStatesByWindow)
    }

    private fun validateModel2(guiInteractionSequence: LinkedList<Interaction<*>>): Boolean {

        return true
    }

    private fun     validateModel(guiInteraction: Interaction<*>, actionGUIState: State<*>): Boolean {
        if (guiInteraction.actionType == "TextInsert")
            return true
        if (guiInteraction.actionType == "FetchGUI")
            return true
        val actionAbstractState = getAbstractState(actionGUIState)
        if (actionAbstractState == null)
            return true
        if (actionAbstractState.isRequestRuntimePermissionDialogBox)
            return true
        val abstractTransition = atuaMF.dstg.edges(actionAbstractState).find {
            it.label.interactions.contains(guiInteraction)
                    && !it.label.isImplicit
        }?.label
        if (abstractTransition == null)
            return true
        if (abstractTransition.dest.attributeValuationMaps.isEmpty())
            return true
      /*  if (goBackAbstractActions.contains(abstractTransition.abstractAction))
            return true*/
        val similarAbstractTransitions = ArrayList<AbstractTransition>()
        similarAbstractTransitions.add(abstractTransition)

        if (similarAbstractTransitions.isEmpty())
            return true
        val similarATFromActionAS =
            getType1SimilarAbstractTransitions(actionAbstractState, abstractTransition, HashSet())
        similarAbstractTransitions.addAll(similarATFromActionAS)
        similarAbstractTransitions.removeIf {
            AbstractionFunction2.INSTANCE.isAbandonedAbstractTransition(actionAbstractState.activity, it)
                    || it.activated == false
        }
        val sameWindowsAbstractStates = getSimilarAbstractStates(actionAbstractState, abstractTransition)
        val similarAbstractStates = getSlightlyDifferentAbstractStates(actionAbstractState, sameWindowsAbstractStates)
        similarAbstractStates.forEach {
            val similarATs = getType1SimilarAbstractTransitions(it, abstractTransition, HashSet())
            similarAbstractTransitions.addAll(similarATs)
        }
        similarAbstractTransitions.removeIf {
            AbstractionFunction2.INSTANCE.isAbandonedAbstractTransition(actionAbstractState.activity, it)
                    || it.activated == false
        }
        if (abstractTransition.abstractAction.attributeValuationMap == null) {
            similarAbstractTransitions.forEach {
                if (it != abstractTransition) {
                    it.activated = false
                }
            }
            return true
        }
        /*val abstractStates = if (guiInteraction.targetWidget == null) {
            ABSTRACT_STATES.filterNot{ it is VirtualAbstractState}. filter { it.window == actionAbstractState.window}
        } else {
            val widgetGroup = actionAbstractState.getWidgetGroup(guiInteraction.targetWidget!!, actionGUIState)
            ABSTRACT_STATES.filterNot { it is VirtualAbstractState }. filter { it.window == actionAbstractState.window
                    && it.widgets.contains(widgetGroup)}
        }*/
        val userInputs = abstractTransition.userInputs


        //val abstractStates = arrayListOf<AbstractState>(actionAbstractState)

        if (abstractTransition.abstractAction.isWebViewAction()) {
            similarAbstractTransitions.removeIf {
                if (it.data is Widget && it != abstractTransition) {
                    (it.data as Widget).nlpText != guiInteraction.targetWidget!!.nlpText
                } else
                    false
            }
        }
        val distinctAbstractInteractions1 = similarAbstractTransitions.groupBy { it.dest.window }
        if (distinctAbstractInteractions1.size > 1) {
            if (abstractTransition.abstractAction.isWebViewAction()) {
                similarAbstractTransitions.forEach {
                    if (it != abstractTransition) {
                        it.activated = false
                    }
                }
                return true
            }

                return false
        }
        similarATFromActionAS.clear()
        similarATFromActionAS.addAll(
            getType1SimilarAbstractTransitions(actionAbstractState, abstractTransition, userInputs)
        )
        similarATFromActionAS.add(abstractTransition)
        val distinctAbstractInteractions2 = similarATFromActionAS.groupBy { it.dest }
        if (distinctAbstractInteractions2.size > 1) {
            var lv1Attributes: Set<Map<AttributeType, String>>? = null
            var valid = true
            // get the most 2 recent abstract transitions
            // if the destination is relatively similar, the non-determination
            // is due to the state of the destinationn windows itself
            val sorted =
                similarATFromActionAS.sortedByDescending { it.interactions.map { it.endTimestamp }.maxOrNull() }.take(2)
            val first = sorted.first().dest
            val lvAttributes1 = first.extractGeneralAVMs()
            sorted.takeLast(sorted.size-1) .forEach {
                val dest = it.dest
                val isSimilar = dest.isSimlarAbstractState(lvAttributes1,0.8)
                if (!isSimilar) {
                    valid = false
                }
            }
            if (!valid && abstractTransition.abstractAction.isWebViewAction()) {
                similarAbstractTransitions.forEach {
                    if (it != abstractTransition) {
                        it.activated = false
                    }
                }
                return true
            }
            return valid
        }

        /*val abstractStates = arrayListOf(actionAbstractState)
        similartAbstractEdges.clear()
        abstractStates.addAll(getSimilarAbstractStates(actionAbstractState, abstractTransition).filter { it.attributeValuationMaps.contains(abstractTransition.abstractAction.attributeValuationMap!!) })
        similartAbstractEdges.add(abstractTransition)
        //validate going to the same window
        getType2SimilarAbstractTransition(abstractStates, abstractTransition, similartAbstractEdges)

        val distinctAbstractInteractions1 = similartAbstractEdges.groupBy { it.dest.window }
        if (distinctAbstractInteractions1.size > 1) {
            if (distinctAbstractInteractions1.any { it.key is Dialog && it.value.all { it.dest.isRequestRuntimePermissionDialogBox } }
                    && distinctAbstractInteractions1.size == 2) {
                val requestPermissionTransitions = distinctAbstractInteractions1.filter { it.value.all { it.dest.isRequestRuntimePermissionDialogBox } }.values.flatten()
                if (requestPermissionTransitions.size == 1) {
                    distinctAbstractInteractions1
                            .filter { it.value.all { !it.dest.isRequestRuntimePermissionDialogBox } }
                            .values.flatten().forEach {
                        it.requiringPermissionRequestTransition = requestPermissionTransitions.single()
                    }
                    return true
                }
            }
            return false
        }*/
        return true
    }



    private fun getType2SimilarAbstractTransition(
        sourceAbstractStates: List<AbstractState>,
        abstractTransition: AbstractTransition,
        output: ArrayList<AbstractTransition>
    ) {
        sourceAbstractStates.forEach {
            val similarEdges = it.abstractTransitions.filter {
                it != abstractTransition
                        && it.abstractAction == abstractTransition.abstractAction
                        /*&& it.label.prevWindow == abstractTransition.label.prevWindow*/
                        && it.requiringPermissionRequestTransition == abstractTransition.requiringPermissionRequestTransition
                        && it.isExplicit()
            }
            similarEdges.forEach {
                /* val similarEdgeCondition = autautMF.abstractTransitionGraph.edgeConditions[it]!!
                 if (similarEdgeCondition.equals(edgeCondition)) {
                     abstractEdges.add(it)
                 }*/
                output.add(it)
            }
        }
    }

    // Same input in an abstract state brings App to different abstract states
    private fun getType1SimilarAbstractTransitions(
        sourceState: AbstractState,
        abstractTransition: AbstractTransition,
        userInputs: HashSet<HashMap<UUID, String>>,
        isImplicit: Boolean = false
    ): ArrayList<AbstractTransition> {
        val output = ArrayList<AbstractTransition>()
        val similarExplicitEdges = sourceState.abstractTransitions.filter {
            it != abstractTransition
                    && it.activated == true
                    && it.abstractAction == abstractTransition.abstractAction
                    && !it.dest.isRequestRuntimePermissionDialogBox
                    /*&& it.data == abstractTransition.data*/
                    /*&& it.label.prevWindow == abstractTransition.label.prevWindow*/
                    && it.requiringPermissionRequestTransition == abstractTransition.requiringPermissionRequestTransition
                    && ((it.modelVersion == ModelVersion.RUNNING && it.interactions.isNotEmpty() )
                        || (it.modelVersion == ModelVersion.BASE))
            /*&& (it.label.inputGUIStates.intersect(abstractTransition.label.inputGUIStates).isNotEmpty()
                        || abstractTransition.label.inputGUIStates.isEmpty())*/
        }
        similarExplicitEdges.forEach {
            output.add(it)
        }
        return output
    }

    private fun getType2SimilarAbstractTransitions(
        sourceState: AbstractState,
        abstractTransition: AbstractTransition,
        userInputs: HashSet<HashMap<UUID, String>>,
        isImplicit: Boolean = false
    ): ArrayList<AbstractTransition> {
        val output = ArrayList<AbstractTransition>()
        val similarExplicitEdges = sourceState.abstractTransitions.filter {
            it != abstractTransition
                    && it.activated == true
                    && it.abstractAction == abstractTransition.abstractAction
                    && !it.dest.isRequestRuntimePermissionDialogBox
                    /*&& it.data == abstractTransition.data*/
                    /*&& it.label.prevWindow == abstractTransition.label.prevWindow*/
                    && it.requiringPermissionRequestTransition == abstractTransition.requiringPermissionRequestTransition
                    && ((it.modelVersion == ModelVersion.RUNNING && it.interactions.isNotEmpty() )
                    || (it.modelVersion == ModelVersion.BASE))
                    && (!it.guardEnabled
                    || it.dependentAbstractStates.intersect(abstractTransition.dependentAbstractStates).isNotEmpty())
                    && (it.userInputs.isEmpty() || userInputs.isEmpty()
                    || it.userInputs.intersect(userInputs).isNotEmpty())
            /*&& (it.label.inputGUIStates.intersect(abstractTransition.label.inputGUIStates).isNotEmpty()
                        || abstractTransition.label.inputGUIStates.isEmpty())*/
        }
        similarExplicitEdges.forEach {
            output.add(it)
        }
        return output
    }

    fun getSimilarAbstractStates(
        abstractState: AbstractState,
        abstractTransition: AbstractTransition
    ): List<AbstractState> {
        val similarAbstractStates = ABSTRACT_STATES.filter {
            it !is VirtualAbstractState
                    && it.guiStates.isNotEmpty()
                    && it.window == abstractState.window
                    && it != abstractState
                    && it.isOpeningKeyboard == abstractState.isOpeningKeyboard
                    && it.rotation == abstractState.rotation
                    && it.isOpeningMenus == abstractState.isOpeningMenus
        }
        if (!abstractTransition.abstractAction.isWidgetAction()) {
            return similarAbstractStates
        } else
            return similarAbstractStates.filter { it.attributeValuationMaps.contains(abstractTransition.abstractAction.attributeValuationMap) }
    }

    private fun getSimilarAbstractStates(abstractState: AbstractState): List<AbstractState> {
        val similarAbstractStates = ABSTRACT_STATES.filter {
            it !is VirtualAbstractState
                    && it.window == abstractState.window
                    && it != abstractState
                    && it.isOpeningKeyboard == abstractState.isOpeningKeyboard
                    && it.rotation == abstractState.rotation
                    && it.isOpeningMenus == abstractState.isOpeningMenus
                    && (it.guiStates.isNotEmpty() || it.modelVersion == ModelVersion.BASE)
        }
        return similarAbstractStates
    }

    fun rebuildModel(window: Window, affectedWidget: EWTGWidget) {
        //reset virtual abstract state
        val toRemoveVirtualAbstractState = ABSTRACT_STATES.filter { it is VirtualAbstractState && it.window == window }
        toRemoveVirtualAbstractState.forEach {
            atuaMF.dstg.removeVertex(it)
            it.attributeValuationMaps.clear()
            it.abstractTransitions.clear()
            //initAbstractInteractions(it)
        }
        val oldAbstractStatesByWindow = HashMap<Window, ArrayList<AbstractState>>()
        // attrValSetsFrequency.get(window)?.clear()
        //AttributeValuationMap.allWidgetAVMHashMap.get(window)?.clear()

        val oldAbstractStates = ABSTRACT_STATES.filter {
            it.window == window && it !is VirtualAbstractState
                    && it.guiStates.isNotEmpty()
        }
        val obsoleteAbstractStates = oldAbstractStates.filter {
            it.EWTGWidgetMapping.values.any {
                it == affectedWidget
                        || it.className == "android.widget.EditText"
            }
        }
        oldAbstractStatesByWindow.put(window, ArrayList(obsoleteAbstractStates))
        recomputeAbstractStatesAndAbstractTransitions(oldAbstractStatesByWindow)

        //get all related abstract state

        updateLaunchAndResetTransition()

        PathFindingHelper.allAvailableTransitionPaths.forEach { t, u ->
            val transitionPaths = u.toList()
            transitionPaths.forEach { transitionPath ->
                if (!ABSTRACT_STATES.contains(transitionPath.root)) {
                    u.remove(transitionPath)
                } else if (transitionPath.path.values.map { it.dest }.any { !ABSTRACT_STATES.contains(it) }) {
                    u.remove(transitionPath)
                }
            }
        }

    }

    fun updateLaunchAndResetTransition() {
        val allAbstractStates = ABSTRACT_STATES.filter { it !is VirtualAbstractState }
        val launchState = launchStates[LAUNCH_STATE.NORMAL_LAUNCH]
        if (launchState == null)
            return
        val resetState = launchStates[LAUNCH_STATE.RESET_LAUNCH]
        if (resetState == null)
            return
        val launchAbstractState = getAbstractState(launchState!!)
        val resetAbstractState = getAbstractState(resetState!!)
        if (launchAbstractState == null)
            throw Exception("Launch Abstract State is null")
        if (resetAbstractState == null)
            throw Exception("Reset Abstract State is null")
        allAbstractStates.forEach { abstractState ->
//            updateOrCreateLaunchAppTransition(abstractState, launchAbstractState!!)
            updateOrCreateResetAppTransition(abstractState, resetAbstractState!!)
        }
    }

    private fun recomputeAbstractStatesAndAbstractTransitions(oldAbstractStatesByWindow: Map<Window, ArrayList<AbstractState>>) {
        val old_newAbstractStates = HashMap<AbstractState, ArrayList<AbstractState>>()
        val recomputeGuistates = ArrayList<State<*>>()
        recomputeAbstractStates(oldAbstractStatesByWindow, recomputeGuistates, old_newAbstractStates)
        oldAbstractStatesByWindow.keys.forEach {
            it.resetMeaningfulScore()
            it.inputs.forEach {
                it.resetMeaningfulScore()
            }
        }
        old_newAbstractStates.values.flatten().distinct().map { it.getAvailableActions() }.flatten().distinct().forEach { action->
            action.reset()
            if (action.attributeValuationMap!=null)
                action.attributeValuationMap.setActionCount(action,0)
        }
        val missingGuistates = recomputeGuistates.filter { state ->
            ABSTRACT_STATES.all { !it.guiStates.contains(state) }
        }
        if (missingGuistates.isNotEmpty())
            throw Exception("GUI states are not fully recomputed")
        var computeInteractionsTime: Long = 0
        var computeGuiStateTime: Long = 0

        val processedGUIInteractions = ArrayList<Interaction<Widget>>()
        val newEdges = ArrayList<Edge<AbstractState, AbstractTransition>>()
        val inEdgeMap = HashMap<AbstractState, HashSet<Edge<AbstractState, AbstractTransition>>>()
        old_newAbstractStates.keys.forEach { abstractState ->
            inEdgeMap.put(abstractState, HashSet())
        }
        atuaMF.dstg.edges().forEach {
            if (inEdgeMap.containsKey(it.destination?.data)) {
                inEdgeMap[it.destination?.data]!!.add(it)
            }
        }

        //compute new abstract interactions
        val newAbstractTransitions = ArrayList<AbstractTransition>()
        measureTimeMillis {
            recomputeAbstractTransitions(
                old_newAbstractStates,
                inEdgeMap,
                newAbstractTransitions,
                processedGUIInteractions,
                newEdges
            )
        }.also {
            log.info("Recompute Abstract Transitions took $it ms")
        }
        removeObsoleteAbstractStatesAndAbstractTransitions(oldAbstractStatesByWindow)
        /*       newAbstractTransitions.distinct().forEach {abstractTransition->
                   if (abstractTransition.interactions.isNotEmpty()) {
                       abstractTransition.interactions.forEach { interaction ->
                           val resState = atuaMF.stateList.find { it.stateId ==  interaction.resState}
                           val transitionId = atuaMF.interactionsTracing.get(listOf(interaction))
                           addImplicitAbstractInteraction(resState,
                                   abstractTransition = abstractTransition,
                                   transitionId = transitionId)
                       }
                   } else {
                       addImplicitAbstractInteraction(currentState =  null,
                               abstractTransition = abstractTransition,
                               transitionId = null)
                   }
               }*/
    }

    private fun removeObsoleteAbstractStatesAndAbstractTransitions(oldAbstractStatesByWindow: Map<Window, ArrayList<AbstractState>>) {
        val oldAbstractStates = ArrayList(oldAbstractStatesByWindow.values.flatten())
        oldAbstractStatesByWindow.forEach {
            it.value.forEach {
                if (it.guiStates.isEmpty() && !it.loadedFromModel) {
                    ABSTRACT_STATES.remove(it)
                    oldAbstractStates.remove(it)
                    atuaMF.dstg.removeVertex(it)
                }
            }
        }
        ABSTRACT_STATES.forEach {
            it.abstractTransitions.removeIf { at ->
                !ABSTRACT_STATES.contains(at.dest).also {
                    if (at.isExplicit()) {
                        atuaMF.dstg.removeAbstractActionEnabiblity(at,atuaMF)
                    }
                }
            }
        }
    }

    private fun recomputeAbstractTransitions(
        old_newAbstractStates: HashMap<AbstractState, ArrayList<AbstractState>>,
        inEdgeMap: HashMap<AbstractState, HashSet<Edge<AbstractState, AbstractTransition>>>,
        newAbstractTransitions: ArrayList<AbstractTransition>,
        processedGUIInteractions: ArrayList<Interaction<Widget>>,
        newEdges: ArrayList<Edge<AbstractState, AbstractTransition>>
    ) {
        var guiInteractionCount = 0
        old_newAbstractStates.entries.forEach {
            val oldAbstractState = it.key
            val newAbstractStates = it.value
            // process out-edges

            val outAbstractEdges = atuaMF.dstg.edges(oldAbstractState).toMutableList()
            outAbstractEdges.filter { it.label.modelVersion == ModelVersion.RUNNING && it.label.isImplicit }.forEach {
                atuaMF.dstg.remove(it)
                it.source.data.abstractTransitions.remove(it.label)
            }
            val inAbstractEdges = inEdgeMap[oldAbstractState]!!
            inAbstractEdges.filter { it.label.modelVersion == ModelVersion.RUNNING && it.label.isImplicit }.forEach {
                atuaMF.dstg.remove(it)
                it.source.data.abstractTransitions.remove(it.label)
            }
            val toUpdateMeaningfulScoreActions = ArrayList<AbstractAction>()
            val explicitOutAbstractEdges = outAbstractEdges.filter { it.label.interactions.isNotEmpty() }

            explicitOutAbstractEdges.groupBy { it.label.abstractAction }. forEach {
                it.key.reset()
                toUpdateMeaningfulScoreActions.add(it.key)
            }
            explicitOutAbstractEdges.forEach { oldAbstractEdge ->

                if (oldAbstractEdge.label.abstractAction.actionType == AbstractActionType.RESET_APP) {
                    atuaMF.dstg.remove(oldAbstractEdge)
                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                } else if (oldAbstractEdge.label.abstractAction.isActionQueue()) {
                    // Remove the edge first
                    guiInteractionCount += 1
                    atuaMF.dstg.remove(oldAbstractEdge)
                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                    val newAbstractTransition = recomputeActionQueueAbstractTransition(oldAbstractEdge)
                    newAbstractTransitions.add(newAbstractTransition)
                } else {
//                    val isTarget = oldAbstractState.targetActions.contains(oldAbstractEdge.label.abstractAction)
                    val interactions = oldAbstractEdge.label.interactions.toList()
                    interactions.forEach { interaction ->
                        if (processedGUIInteractions.contains(interaction)) {
                            //log.debug("Processed interaction in refining model")
                        } else {
                            guiInteractionCount += 1
                            processedGUIInteractions.add(interaction)
                            val newEdge = deriveGUIInteraction(interaction, oldAbstractEdge,toUpdateMeaningfulScoreActions)

                            if (newEdge != null) {
                                newEdges.add(newEdge)
                                if (oldAbstractEdge.label != newEdge.label) {
                                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                                    atuaMF.dstg.remove(oldAbstractEdge)
                                    atuaMF.dstg.removeAbstractActionEnabiblity(oldAbstractEdge.label,atuaMF)
                                }
                                newAbstractTransitions.add(newEdge.label)

                            }
                        }
                    }
                }
            }

            // process in-edges

            val explicitInAbstractEdges = inAbstractEdges.filter { it.label.interactions.isNotEmpty() }

            explicitInAbstractEdges.forEach { oldAbstractEdge ->
                if (oldAbstractEdge.label.abstractAction.actionType == AbstractActionType.RESET_APP) {
                    atuaMF.dstg.remove(oldAbstractEdge)
                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                    // log.debug("LaunchApp or ResetApp interaction. Do nothing.")
                } else if (oldAbstractEdge.label.abstractAction.isActionQueue()) {
                    guiInteractionCount += 1
                    // Remove the edge first
                    atuaMF.dstg.remove(oldAbstractEdge)
                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                    val newAbstractTransition = recomputeActionQueueAbstractTransition(oldAbstractEdge)
                    newAbstractTransitions.add(newAbstractTransition)
                } else {
//                    val isTarget = oldAbstractState.targetActions.contains(oldAbstractEdge.label.abstractAction)
                    val interactions = oldAbstractEdge.label.interactions.toList()
                    interactions.forEach { interaction ->
                        if (processedGUIInteractions.contains(interaction)) {
                            // log.debug("Processed interaction in refining model")
                        } else {
                            guiInteractionCount += 1
                            processedGUIInteractions.add(interaction)
                            val newEdge = deriveGUIInteraction(interaction, oldAbstractEdge,toUpdateMeaningfulScoreActions)
                            if (newEdge != null) {
                                newAbstractTransitions.add(newEdge.label)
                                newEdges.add(newEdge)
                                if (oldAbstractEdge.label != newEdge.label) {
                                    oldAbstractEdge.source.data.abstractTransitions.remove(oldAbstractEdge.label)
                                    atuaMF.dstg.remove(oldAbstractEdge)
                                    atuaMF.dstg.removeAbstractActionEnabiblity(oldAbstractEdge.label,atuaMF)
                                }
                            }

                        }
                    }
                }
            }
        }
        log.info("Number of recomputed gui interactions: $guiInteractionCount")
    }

    private fun recomputeActionQueueAbstractTransition(oldAbstractEdge: Edge<AbstractState, AbstractTransition>): AbstractTransition {
        val interactionQueue = oldAbstractEdge.label.data as List<Interaction<*>>
        val interaction = interactionQueue.first()
        val tracing = atuaMF.interactionsTracingMap.get(interactionQueue)
        var sourceState: State<*>? = null
        var destState: State<*>? = null
        sourceState = atuaMF.stateList.find { it.stateId == interaction.prevState }!!
        destState = atuaMF.stateList.find { it.stateId == interaction.resState }!!

        var sourceAbstractState = getAbstractState(sourceState)

        if (sourceAbstractState == null)
            throw Exception("Cannot find new prevState's abstract state")

        val destinationAbstractState = getAbstractState(destState)
        if (destinationAbstractState == null) {
            throw Exception("Cannot find new resState's abstract state")
        }
        val modelVersion = oldAbstractEdge.label.modelVersion
        val newAbstractTransition = AbstractTransition(
            abstractAction = oldAbstractEdge.label.abstractAction,
            data = oldAbstractEdge.label.data,
            interactions = oldAbstractEdge.label.interactions,
            /*prevWindow = oldAbstractEdge.label.prevWindow,*/
            fromWTG = oldAbstractEdge.label.fromWTG,
            source = sourceAbstractState,
            dest = destinationAbstractState,
            isImplicit = false,
            modelVersion = modelVersion
        )
        newAbstractTransition.copyPotentialInfoFrom(oldAbstractEdge.label)
        sourceAbstractState.abstractTransitions.add(newAbstractTransition)
        atuaMF.dstg.add(sourceAbstractState, destinationAbstractState, newAbstractTransition)
        // addImplicitAbstractInteraction(destState, newAbstractTransition, tracing)
        return newAbstractTransition
    }

    private fun deriveGUIInteraction(
        interaction: Interaction<*>,
        oldAbstractEdge: Edge<AbstractState, AbstractTransition>,
        toUpdateMeaningfulScoreActions: List<AbstractAction>
    ): Edge<AbstractState, AbstractTransition>? {
        var sourceState: State<*>? = null
        var destState: State<*>? = null
        sourceState = atuaMF.stateList.find { it.stateId == interaction.prevState }!!
        destState = atuaMF.stateList.find { it.stateId == interaction.resState }!!

        var sourceAbstractState = getAbstractState(sourceState)

        if (sourceAbstractState == null)
            throw Exception("Cannot find new prevState's abstract state")

        val destinationAbstractState = getAbstractState(destState)
        /* var destinationAbstractState = if (oldAbstractEdge.destination!!.data.window == staticNode) {
                                        stateId_AbstractStateMap[interaction.resState]
                                    } else {
                                        oldAbstractEdge.destination!!.data
                                    }*/
        if (destinationAbstractState == null) {
            throw Exception("Cannot find new resState's abstract state")
        }
        val newEdge = updateAbstractTransition(
            oldAbstractEdge,
            sourceAbstractState!!,
            destinationAbstractState!!,
            interaction,
            sourceState!!,
            destState!!,
            toUpdateMeaningfulScoreActions
        )
        if (newEdge != null && atuaMF.reuseBaseModel)
            ModelBackwardAdapter.instance.checkingEquivalence(
                destState,
                destinationAbstractState,
                newEdge.label,
                null,
                atuaMF
            )
        return newEdge
    }

    private fun recomputeAbstractStates(
        oldAbstractStatesByWindow: Map<Window, ArrayList<AbstractState>>,
        recomputeGuistates: ArrayList<State<*>>,
        old_newAbstractStates: HashMap<AbstractState, ArrayList<AbstractState>>
    ) {
        var guiStateCount = 0
        oldAbstractStatesByWindow.forEach { window, oldAbstractStates ->
            val processedGuiState = HashSet<State<*>>()
            val derivedWidgets = AttributeValuationMap.allWidgetAVMHashMap[window]!!
            val oldGuiStates = HashMap<AbstractState, List<State<*>>>()
            oldAbstractStates.forEach {
                oldGuiStates.put(it, ArrayList(it.guiStates))
                recomputeGuistates.addAll(it.guiStates)
                it.guiStates.forEach {
                    guiState_AbstractState_Map.remove(it.stateId)
                    it.widgets.forEach {
                        derivedWidgets.remove(it)
                    }
                }
                it.guiStates.clear()
                it.attributeValuationMaps.forEach { attrValSetsFrequency[window]?.remove(it) }
            }
            var getGuiStateTime: Long = 0
            measureTimeMillis {
                //compute new AbstractStates for each old one
                oldAbstractStates.forEach { oldAbstractState ->
                    val newAbstractStates = ArrayList<AbstractState>()
                    oldGuiStates[oldAbstractState]!!.filterNot { processedGuiState.contains(it) }.forEach { guiState ->
                        guiStateCount += 1
                        processedGuiState.add(guiState)
                        /*var internet = when (oldAbstractState.internet) {
                            InternetStatus.Enable -> true
                            InternetStatus.Disable -> false
                            else -> true
                        }*/
                        val abstractState = getOrCreateNewAbstractState(
                            guiState,
                            oldAbstractState.activity,
                            oldAbstractState.rotation,
                            oldAbstractState.window,
                            true
                        )
                        //val abstractState = refineAbstractState(possibleAbstractStates, guiState, oldAbstractState.window, oldAbstractState.rotation, oldAbstractState.internet)
                        atuaMF.abstractStateVisitCount.putIfAbsent(abstractState, 0)
                        if (!newAbstractStates.contains(abstractState)) {
                            newAbstractStates.add(abstractState)
                            atuaMF.abstractStateVisitCount[abstractState] = 1
                        } else {
                            atuaMF.abstractStateVisitCount[abstractState] =
                                atuaMF.abstractStateVisitCount[abstractState]!! + 1
                        }
                        mapGuiStateToAbstractState(abstractState, guiState)
                        if (launchStates[LAUNCH_STATE.RESET_LAUNCH] == guiState) {
                            abstractState.isInitalState = true
                        }
                    }
                    old_newAbstractStates.put(oldAbstractState, newAbstractStates)
                }
                val allGuiStatesRebuilt = oldGuiStates.map { it.value }.flatten().all {
                    guiState_AbstractState_Map.containsKey(it.stateId)
                }
                if (!allGuiStatesRebuilt)
                    throw Exception()
                val toRemoveAbstractStates = oldAbstractStates.filter { old ->
                    old_newAbstractStates.values.find { it.contains(old) } == null
                }
                /*            toRemoveAbstractStates.forEach {
                                ABSTRACT_STATES.remove(it)
                                //it.abstractTransitions.removeIf { it.isImplicit}
                            }*/

            }.let {
                log.info("Deriving $guiStateCount GUIStates took $it millis")
            }

        }
    }

    private fun updateOrCreateResetAppTransition(abstractState: AbstractState, resetAbstractState: AbstractState) {
        val resetInteractions = atuaMF.dstg.edges(abstractState).filter {
            it.label.abstractAction.actionType == AbstractActionType.RESET_APP
        }
        if (resetInteractions.isNotEmpty()) {
            resetInteractions.forEach {
                atuaMF.dstg.remove(it)
                it.source.data.abstractTransitions.remove(it.label)
            }

        }
        val resetAction = AbstractAction.getOrCreateAbstractAction(
            actionType = AbstractActionType.RESET_APP,
            window = Launcher.getOrCreateNode()
        )
        val abstractInteraction = AbstractTransition(
            abstractAction = resetAction,
            isImplicit = true,
            /*prevWindow = null,*/
            source = abstractState,
            dest = resetAbstractState
        )
        atuaMF.dstg.add(abstractState, resetAbstractState, abstractInteraction)
        abstractState.abstractTransitions.add(abstractInteraction)
    }

    private fun updateOrCreateLaunchAppTransition(abstractState: AbstractState, launchAbstractState: AbstractState) {
        var needCreateImplicitTransition = true
        val explicitLaunchTransitions = atuaMF.dstg.edges(abstractState).filter {
            it.label.abstractAction.actionType == AbstractActionType.LAUNCH_APP
                    && it.label.interactions.isNotEmpty()
        }
        explicitLaunchTransitions.forEach {
            if (it.label.dest != launchAbstractState)
                it.label.activated = false
            else
                needCreateImplicitTransition = false
        }
        val implicitlaunchInteractions = atuaMF.dstg.edges(abstractState).filter {
            it.label.abstractAction.actionType == AbstractActionType.LAUNCH_APP
                    && it.label.interactions.isEmpty()
        }
        if (implicitlaunchInteractions.isNotEmpty()) {
            implicitlaunchInteractions.forEach {
                atuaMF.dstg.remove(it)
                it.source.data.abstractTransitions.remove(it.label)
            }

        }
        if (needCreateImplicitTransition) {
            val launchAction = AbstractAction.getOrCreateAbstractAction(
                actionType = AbstractActionType.LAUNCH_APP,
                window = abstractState.window
            )

            val isImplicit = true
            val abstractInteraction = AbstractTransition(
                abstractAction = launchAction,
                isImplicit = isImplicit,
                /*prevWindow = null,*/
                source = abstractState,
                dest = launchAbstractState
            )
            abstractState.abstractTransitions.add(abstractInteraction)
            atuaMF.dstg.add(abstractState, launchAbstractState, abstractInteraction)
        }

    }

    private fun updateAbstractTransition(
        oldAbstractEdge: Edge<AbstractState, AbstractTransition>,
        sourceAbstractState: AbstractState,
        destinationAbstractState: AbstractState,
        interaction: Interaction<*>,
        sourceState: State<*>,
        destState: State<*>,
        toUpdateMeaningfulScoreActions: List<AbstractAction>
    ): Edge<AbstractState, AbstractTransition>? {
        //Extract text input widget data
        var newAbstractionTransition: AbstractTransition? = null
        var newEdge: Edge<AbstractState, AbstractTransition>? = null
        val condition = HashMap(Helper.extractInputFieldAndCheckableWidget(sourceState))
        val tracing = atuaMF.interactionsTracingMap.get(listOf(interaction))
        val prevWindowAbstractState: AbstractState?
        if (tracing == null || oldAbstractEdge.label.abstractAction.actionType == AbstractActionType.LAUNCH_APP) {
            prevWindowAbstractState = null
        } else if (tracing.second - 1 <= 1) {
            prevWindowAbstractState = null
        } else {
            val traveredInteraction =
                atuaMF.interactionsTracingMap.entries.find { it.value == Pair(tracing.first, tracing.second - 1) }
            if (traveredInteraction == null)
                throw Exception()
            if (!atuaMF.interactionPrevWindowStateMapping.containsKey(traveredInteraction.key.last())) {
                prevWindowAbstractState = null
            } else {
                val prevWindowState = atuaMF.interactionPrevWindowStateMapping.get(traveredInteraction.key.last())!!
                prevWindowAbstractState = getAbstractState(prevWindowState)
            }
        }
        if (!oldAbstractEdge.label.abstractAction.isWidgetAction()) {
            //Reuse Abstract action
            val abstractAction = AbstractAction.getOrCreateAbstractAction(
                oldAbstractEdge.label.abstractAction.actionType,
                interaction, sourceState, sourceAbstractState, null, atuaMF
            )
            /*if (isTarget) {
                sourceAbstractState.targetActions.add(abstractAction)
            }*/
            /*val interactionData = AbstractTransition.computeAbstractTransitionData(abstractAction.actionType,
                    interaction, sourceState, sourceAbstractState, atuaMF)*/
            val interactionData = oldAbstractEdge.label.data
            //check if the interaction was created
            val exisitingAbstractTransition = AbstractTransition.findExistingAbstractTransitions(
                sourceAbstractState.abstractTransitions.toList(),
                abstractAction,
                sourceAbstractState,
                destinationAbstractState
            )
            if (exisitingAbstractTransition == null) {
                //Create explicit edge for linked abstractState
                val pair = createNewAbstractTransitionForNonWidgetInteraction(
                    abstractAction,
                    interactionData,
                    sourceAbstractState,
                    destinationAbstractState,
                    interaction,
                    oldAbstractEdge,
                    newEdge,
                    condition,
                    prevWindowAbstractState
                )
                newAbstractionTransition = pair.first
                newEdge = pair.second
            } else {
                newEdge = atuaMF.dstg.add(sourceAbstractState, destinationAbstractState, exisitingAbstractTransition)
                newAbstractionTransition = exisitingAbstractTransition
                exisitingAbstractTransition.interactions.add(interaction)
                if (oldAbstractEdge.label.inputGUIStates.isNotEmpty()) {
                    newAbstractionTransition.inputGUIStates.add(interaction.prevState)
                }
                if (tracing != null) {
                    newAbstractionTransition.tracing.add(tracing)
                }
                /*if (prevWindowAbstractState != null)
                    exisitingAbstractTransition.dependentAbstractStates.add(prevWindowAbstractState)*/
                exisitingAbstractTransition.computeGuaranteedAVMs()
                /*else
                    log.debug("Cannot get prevWindowAbstractState")*/
            }
        } else {

            //get widgetgroup
            var newAttributeValuationSet =
                sourceAbstractState.getAttributeValuationSet(interaction.targetWidget!!, sourceState, atuaMF)
/*            if (newAttributeValuationSet == null) {
                newAttributeValuationSet = oldAbstractEdge.label.abstractAction.attributeValuationSet
            }*/
            /*if (newAttributeValuationSet == null) {
                val guiTreeRectangle = Helper.computeGuiTreeDimension(sourceState)
                var isOptionsMenu = if (!Helper.isDialog(sourceAbstractState.rotation, guiTreeRectangle, sourceState, atuaMF))
                    Helper.isOptionsMenuLayout(sourceState)
                else
                    false
                val newAttributePath = AbstractionFunction2.INSTANCE.reduce(interaction.targetWidget!!,
                        sourceState,
                        oldAbstractEdge.source.data.EWTGWidgetMapping.get(oldAbstractEdge.label.abstractAction.attributeValuationMap!!)!!,
                        isOptionsMenu,
                        guiTreeRectangle,
                        sourceAbstractState.window,
                        sourceAbstractState.rotation, atuaMF, HashMap(), HashMap())
                newAttributeValuationSet = sourceAbstractState.attributeValuationMaps.find { it.haveTheSameAttributePath(newAttributePath) }
                *//*  newAttributeValuationSet = AttributeValuationSet(newAttributePath, Cardinality.ONE, sourceAbstractState.activity, HashMap())
                  activity_widget_AttributeValuationSetHashMap[sourceAbstractState.activity]!!.put(interaction.targetWidget!!,newAttributeValuationSet)*//*


                //newWidgetGroup.guiWidgets.add(interaction.targetWidget!!)
                //sourceAbstractState.addWidgetGroup(newWidgetGroup)
            }*/
            if (newAttributeValuationSet != null) {
                val abstractAction = AbstractAction.getOrCreateAbstractAction(
                    oldAbstractEdge.label.abstractAction.actionType,
                    interaction,
                    sourceState,
                    sourceAbstractState,
                    newAttributeValuationSet,
                    atuaMF

                )
                val interactionData = AbstractTransition.computeAbstractTransitionData(
                    abstractAction.actionType,
                    interaction, sourceState, sourceAbstractState, atuaMF
                )
                val availableAction = sourceAbstractState.getAvailableActions(sourceState).find {
                    it.equals(abstractAction)
                }
                if (availableAction == null) {
                    sourceAbstractState.addAction(abstractAction)
                }
                //sourceAbstractState.addAction(abstractAction)
           /*     if (isTarget) {
                    sourceAbstractState.targetActions.add(abstractAction)
                }*/
                //check if there is exisiting interaction
                val exisitingAbstractTransition = AbstractTransition.findExistingAbstractTransitions(
                    sourceAbstractState.abstractTransitions.toList(),
                    abstractAction,
                    sourceAbstractState,
                    destinationAbstractState
                )
                if (exisitingAbstractTransition != null) {
                    newEdge =
                        atuaMF.dstg.add(sourceAbstractState, destinationAbstractState, exisitingAbstractTransition)
                    newAbstractionTransition = exisitingAbstractTransition
                    exisitingAbstractTransition.interactions.add(interaction)
                    if (oldAbstractEdge.label.inputGUIStates.isNotEmpty()) {
                        newAbstractionTransition.inputGUIStates.add(interaction.prevState)
                    }
                    if (tracing != null) {
                        newAbstractionTransition.tracing.add(tracing)
                    }
                    /*if (prevWindowAbstractState != null)
                        exisitingAbstractTransition.dependentAbstractStates.add(prevWindowAbstractState)*/
                    exisitingAbstractTransition.computeGuaranteedAVMs()
                } else {
                    //Create explicit edge for linked abstractState
                    val pair = createNewAbstractTransionFromWidgetInteraction(
                        abstractAction,
                        interactionData,
                        sourceAbstractState,
                        destinationAbstractState,
                        interaction,
                        oldAbstractEdge,
                        newEdge,
                        condition,
                        prevWindowAbstractState
                    )
                    newAbstractionTransition = pair.first
                    newEdge = pair.second

                }
            }

        }
        if (newAbstractionTransition != null) {
            // update coverage
            if (atuaMF.guiInteractionCoverage.containsKey(interaction)) {
                val interactionCoverage = atuaMF.guiInteractionCoverage.get(interaction)!!
                interactionCoverage.forEach {
                    newAbstractionTransition.updateUpdateStatementCoverage(it, atuaMF)
                }
            }
            if (tracing != null) {
               newAbstractionTransition.updateDependentAppState(destState,tracing.first,tracing.second,atuaMF)
                updateImplicitAppTransitions(sourceAbstractState,newAbstractionTransition)
            }
            if (toUpdateMeaningfulScoreActions.contains(newAbstractionTransition.abstractAction)) {
                var coverageIncrease = atuaMF!!.statementMF!!.actionIncreasingCoverageTracking[interaction.actionId.toString()]?.size
                if (coverageIncrease == null)
                    coverageIncrease = 0

                newAbstractionTransition.abstractAction.updateMeaningfulScore(
                    interaction,
                    sourceState,
                    destState,
                    coverageIncrease>0,
                    atuaMF.randomInteractions.contains(interaction.actionId),
                    atuaMF)
            }
            newAbstractionTransition.source.increaseActionCount2(newAbstractionTransition.abstractAction,false)
            addImplicitAbstractInteraction(destState, newAbstractionTransition, tracing,sourceState, destState, interaction, false)
            if (newAbstractionTransition.source != newAbstractionTransition.dest
                && !newAbstractionTransition.dest.ignored)
                atuaMF.dstg.updateAbstractActionEnability(newAbstractionTransition, atuaMF)
        }
        return newEdge
    }

    private fun createNewAbstractTransionFromWidgetInteraction(
        abstractAction: AbstractAction,
        interactionData: Any?,
        sourceAbstractState: AbstractState,
        destinationAbstractState: AbstractState,
        interaction: Interaction<*>,
        oldAbstractEdge: Edge<AbstractState, AbstractTransition>,
        newEdge: Edge<AbstractState, AbstractTransition>?,
        condition: HashMap<UUID, String>,
        prevWindowAbstractState: AbstractState?
    ): Pair<AbstractTransition?, Edge<AbstractState, AbstractTransition>?> {
        var newAbstractionTransition1: AbstractTransition
        var newEdge1 = newEdge
        val modelVersion = oldAbstractEdge.label.modelVersion
        newAbstractionTransition1 = AbstractTransition(
            abstractAction = abstractAction,
            isImplicit = false,
            /*prevWindow = oldAbstractEdge.label.prevWindow,*/
            data = interactionData,
            source = sourceAbstractState,
            dest = destinationAbstractState,
            modelVersion = modelVersion
        )
        newAbstractionTransition1.interactions.add(interaction)
        /*if (prevWindowAbstractState != null)
            newAbstractionTransition1.dependentAbstractStates.add(prevWindowAbstractState)
        */
        newAbstractionTransition1.computeGuaranteedAVMs()
        if (oldAbstractEdge.label.inputGUIStates.isNotEmpty()) {
            newAbstractionTransition1.inputGUIStates.add(interaction.prevState)
        }
        newAbstractionTransition1.handlers.putAll(oldAbstractEdge.label.handlers)
        val tracing = atuaMF.interactionsTracingMap.get(listOf(interaction))
        if (tracing != null) {
            newAbstractionTransition1.tracing.add(tracing)
        }
        newEdge1 = atuaMF.dstg.add(
            sourceAbstractState,
            destinationAbstractState,
            newAbstractionTransition1
        )
        if (condition.isNotEmpty())
            if (!newEdge1.label.userInputs.contains(condition))
                newEdge1.label.userInputs.add(condition)
       /* if (newAbstractionTransition1.dependentAbstractStates.map { it.window }
                .contains(newAbstractionTransition1.dest.window)
            && newAbstractionTransition1.guardEnabled == false
        ) {
            newAbstractionTransition1.guardEnabled = true
        }*/
        newAbstractionTransition1.activated = oldAbstractEdge.label.activated
        return Pair(newAbstractionTransition1, newEdge1)
    }

    private fun createNewAbstractTransitionForNonWidgetInteraction(
        abstractAction: AbstractAction,
        interactionData: Any?,
        sourceAbstractState: AbstractState,
        destinationAbstractState: AbstractState,
        interaction: Interaction<*>,
        oldAbstractEdge: Edge<AbstractState, AbstractTransition>,
        newEdge: Edge<AbstractState, AbstractTransition>?,
        condition: HashMap<UUID, String>,
        prevWindowAbstractState: AbstractState?
    ): Pair<AbstractTransition?, Edge<AbstractState, AbstractTransition>?> {
        var newAbstractionTransition: AbstractTransition
        var newEdge1 = newEdge
        val modeVersion = oldAbstractEdge.label.modelVersion
        newAbstractionTransition = AbstractTransition(
            abstractAction = abstractAction,
            isImplicit = false,
            /*prevWindow = oldAbstractEdge.label.prevWindow,*/
            data = interactionData,
            source = sourceAbstractState,
            dest = destinationAbstractState,
            modelVersion = modeVersion
        )
        newAbstractionTransition.interactions.add(interaction)
        /*if (prevWindowAbstractState != null)
            newAbstractionTransition.dependentAbstractStates.add(prevWindowAbstractState)*/
        newAbstractionTransition.computeGuaranteedAVMs()
        if (oldAbstractEdge.label.inputGUIStates.isNotEmpty()) {
            newAbstractionTransition.inputGUIStates.add(interaction.prevState)
        }
        val tracing = atuaMF.interactionsTracingMap.get(listOf(interaction))
        if (tracing != null) {
            newAbstractionTransition.tracing.add(tracing)
        }
        newEdge1 = atuaMF.dstg.add(sourceAbstractState, destinationAbstractState, newAbstractionTransition)
        if (condition.isNotEmpty())
            if (!newEdge1.label.userInputs.contains(condition))
                newEdge1.label.userInputs.add(condition)
    /*    if (newAbstractionTransition.dependentAbstractStates.map { it.window }
                .contains(newAbstractionTransition.dest.window)
            && newAbstractionTransition.guardEnabled == false
        ) {
            newAbstractionTransition.guardEnabled = true
        }*/
        newAbstractionTransition.activated = oldAbstractEdge.label.activated
        return Pair(newAbstractionTransition, newEdge1)
    }


    val widget_StaticWidget = HashMap<Window, HashMap<ConcreteId, ArrayList<EWTGWidget>>>()

    fun addImplicitAbstractInteraction(
        currentState: State<*>?,
        abstractTransition: AbstractTransition,
        transitionId: Pair<Int, Int>?,
        sourceState: State<*>,
        destState: State<*>,
        interaction: Interaction<*>,
        addImplicitAbstractTransitionToOtherStates: Boolean = true
    ) {
        //AutAutMF.log.debug("Add implicit abstract interaction")
        var addedCount = 0
        var processedStateCount = 0
        // add implicit back events
        addedCount = 0
        processedStateCount = 0
        val currentAbstractState = abstractTransition.dest
        val prevAbstractState = abstractTransition.source
        val prevWindowAbstractState = if (transitionId != null)
            atuaMF.getPrevWindowAbstractState(transitionId.first, transitionId.second)
        else
            null
        val p_prevWindowAbstractState = if (transitionId != null && transitionId.second != 0) {
            atuaMF.getPrevWindowAbstractState(transitionId.first, transitionId.second - 1)
        } else
            null
/*        if (p_prevWindowAbstractState != null)
            if (currentAbstractState.window == p_prevWindowAbstractState.window
                && !currentAbstractState.isOpeningMenus
                && !currentAbstractState.isOpeningKeyboard) {
                goBackAbstractActions.add(abstractTransition.abstractAction)
                abstractTransition.guardEnabled = true
                abstractTransition.dependentAbstractStates.add(p_prevWindowAbstractState)
                atuaMF.dstg.abstractActionEnables.remove(abstractTransition.abstractAction)
                val inputs = abstractTransition.source.getInputsByAbstractAction(abstractTransition.abstractAction)
                inputs.forEach {
                    if (!Input.goBackInputs.contains(it))
                        Input.goBackInputs.add(it)
                }
            }*/
        if (transitionId != null && currentState != null
            && !currentAbstractState.isOpeningKeyboard
            && !currentAbstractState.isHomeScreen
            /*&& abstractTransition.abstractAction.actionType != AbstractActionType.PRESS_BACK*/
            && !abstractTransition.abstractAction.isLaunchOrReset()
        ) {
            // val implicitBackWindow = computeImplicitBackWindow(currentAbstractState, prevAbstractState, prevWindow)

            if (prevWindowAbstractState != null) {
                if (!prevWindowAbstractState.isHomeScreen
                    && prevAbstractState != currentAbstractState
                ) {
                    // create implicit pressBack Transition
                    val backAbstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.PRESS_BACK,
                        window = currentAbstractState.window
                    )
                    if (!ignoreImplicitDerivedTransition.any {
                            it.first == currentAbstractState.window
                                    && it.second.actionType == backAbstractAction.actionType
                                    && it.third == prevWindowAbstractState.window
                        }) {
                        createImplicitBackTransition(
                            currentAbstractState,
                            prevWindowAbstractState,
                            backAbstractAction,
                            processedStateCount,
                            prevAbstractState,
                            addedCount
                        )
                    }

                    goBackAbstractActions.filter{ action ->
                        action.window == currentAbstractState.window
                                && action != backAbstractAction
                                && !ignoreImplicitDerivedTransition.any {
                            it.first == currentAbstractState.window
                                    && it.second.actionType == action.actionType
                                    && it.third == prevWindowAbstractState.window
                        }}.forEach { backAction ->
                        createImplicitBackTransition(
                            currentAbstractState,
                            prevWindowAbstractState,
                            backAction,
                            processedStateCount,
                            prevAbstractState,
                            addedCount
                        )

                    }
                }
            }
        }
        if (transitionId != null && currentState != null && currentAbstractState.isOpeningKeyboard) {
            var keyboardClosedAbstractState: AbstractState? =
                atuaMF.getKeyboardClosedAbstractState(currentState, transitionId)
            if (keyboardClosedAbstractState == null) {
                keyboardClosedAbstractState = ABSTRACT_STATES.find {
                    it is VirtualAbstractState && it.window == currentAbstractState.window
                }
            }
            if (keyboardClosedAbstractState != null)
                createImplicitCloseKeyboardTransition(
                    currentAbstractState,
                    keyboardClosedAbstractState,
                    currentState,
                    processedStateCount,
                    addedCount
                )
        }
        if (abstractTransition.abstractAction.actionType == AbstractActionType.SWIPE
            && abstractTransition.abstractAction.attributeValuationMap != null
            && prevAbstractState != currentAbstractState
        ) {
            //check if the swipe action changed the content
            if (currentAbstractState.attributeValuationMaps.contains(abstractTransition.abstractAction.attributeValuationMap!!)) {
                val currentWidgetGroup =
                    currentAbstractState.attributeValuationMaps.find { it == abstractTransition.abstractAction.attributeValuationMap }!!
                if (!currentWidgetGroup.havingSameContent(
                        currentAbstractState,
                        abstractTransition.abstractAction.attributeValuationMap!!,
                        prevAbstractState
                    )
                ) {
                    //add implicit sysmetric action
                    createImplictiInverseSwipeTransition(abstractTransition, currentAbstractState, prevAbstractState)
                }
            }
        }

        if (abstractTransition.abstractAction.actionType == AbstractActionType.ROTATE_UI
            && prevAbstractState != currentAbstractState
            && prevAbstractState.window == currentAbstractState.window
            && prevAbstractState.isOpeningMenus == currentAbstractState.isOpeningMenus
        ) {
            createImplicitInverseRotationTransition(currentAbstractState, prevAbstractState)
        }

/*        if (abstractTransition.abstractAction.actionType == AbstractActionType.ENABLE_DATA
                || abstractTransition.abstractAction.actionType == AbstractActionType.DISABLE_DATA
        ) {
            return
        }
        if (ignoreImplicitDerivedTransition.any { it.first == prevAbstractState.window
                    && it.second == abstractTransition.abstractAction && it.third == currentAbstractState.window})
            return
        //do not add implicit transition if this is Launch/Reset/Swipe
        if (addImplicitAbstractTransitionToOtherStates && consideredForImplicitAbstractStateAction(abstractTransition.abstractAction)) {
            createImplicitTransitionForOtherAbstractStates(prevAbstractState, processedStateCount, abstractTransition)
        }*/
        inferItemActionTransitions(action = abstractTransition.abstractAction.actionType,
        abstractTransition = abstractTransition,
        prevAbstractState = prevAbstractState,
        currentAbstractState = currentAbstractState,
        sourceState = sourceState,
        destState = destState,
        interaction = interaction,
        atuamf = atuaMF)

    }

    private fun createImplicitCloseKeyboardTransition(
        currentAbstractState: AbstractState,
        keyboardClosedAbstractState: AbstractState,
        currentState: State<*>,
        processedStateCount: Int,
        addedCount: Int
    ) {
        val abstractAction = AbstractAction.getOrCreateAbstractAction(
            actionType = AbstractActionType.CLOSE_KEYBOARD,
            window = currentAbstractState.window
        )
        val existingAT = AbstractTransition.findExistingAbstractTransitions(
            abstractTransitionSet = currentAbstractState.abstractTransitions.toList(),
            abstractAction = abstractAction,
            dest = keyboardClosedAbstractState,
            source = currentAbstractState
        )
        if (existingAT != null)
            return
        val newAbstractionTransition = AbstractTransition(
            abstractAction = abstractAction,
            isImplicit = false,
            source = currentAbstractState,
            dest = keyboardClosedAbstractState,
            modelVersion = ModelVersion.RUNNING,
            data = null,
            fromWTG = false
        )
        atuaMF.dstg.add(newAbstractionTransition.source, newAbstractionTransition.dest, newAbstractionTransition)
    }

    private fun consideredForImplicitAbstractStateAction(abstractAction: AbstractAction): Boolean {
        return !abstractAction.isLaunchOrReset()
                && abstractAction.actionType != AbstractActionType.PRESS_BACK
                && abstractAction.actionType != AbstractActionType.MINIMIZE_MAXIMIZE
                && abstractAction.actionType != AbstractActionType.WAIT
                && abstractAction.actionType != AbstractActionType.CLOSE_KEYBOARD
                && abstractAction.actionType != AbstractActionType.ROTATE_UI
                && abstractAction.actionType != AbstractActionType.SWIPE
    }

    private fun computeImplicitBackWindow(
        currentAbstractState: AbstractState,
        prevAbstractState: AbstractState,
        prevWindow: Window?
    ): Window? {
        val implicitBackWindow = if (currentAbstractState.isOpeningKeyboard || currentAbstractState.isOpeningMenus) {
            currentAbstractState.window
        } else if (prevAbstractState.window == currentAbstractState.window) {
            prevWindow
        } else if (currentAbstractState.window == prevWindow) {
            null
        } else if (prevAbstractState.window is Dialog) {
            prevWindow
        } else {
            prevAbstractState.window
        }
        return implicitBackWindow
    }

    private fun inferItemActionTransitions(
        action: AbstractActionType,
        abstractTransition: AbstractTransition,
        prevAbstractState: AbstractState,
        currentAbstractState: AbstractState,
        sourceState: State<*>,
        destState: State<*>,
        interaction: Interaction<*>,
        atuamf: ATUAMF
    ) {
        if (!(abstractTransition.abstractAction.isWidgetAction() &&
            (action == AbstractActionType.CLICK
                || action == AbstractActionType.LONGCLICK
                || action == AbstractActionType.ITEM_CLICK
                || action == AbstractActionType.ITEM_LONGCLICK))) {
            return
        }
        val itemAction = when (action) {
            AbstractActionType.CLICK, AbstractActionType.ITEM_CLICK -> AbstractActionType.ITEM_CLICK
            AbstractActionType.LONGCLICK, AbstractActionType.ITEM_LONGCLICK -> AbstractActionType.ITEM_LONGCLICK
            else -> null
        }
        if (itemAction == null)
            throw Exception("Action $action cannot be inferred as item action.")
        //val parentWidgetGroups = HashSet<WidgetGroup>()
        val widget = interaction.targetWidget!!
        var parentWidget = sourceState.widgets.find { it.id == widget.parentId }
        while (parentWidget!=null) {
            val parentAVS = AttributeValuationMap.allWidgetAVMHashMap[prevAbstractState.window]!!.get(
                parentWidget
            )
            if (parentAVS != null && parentAVS != abstractTransition.abstractAction.attributeValuationMap) {
                if (prevAbstractState.attributeValuationMaps.contains(parentAVS)) {
                    val itemAbstractAction = prevAbstractState.getAvailableActions(sourceState).find {
                        it.actionType == itemAction &&
                                it.attributeValuationMap == parentAVS
                    }

                    if (itemAbstractAction != null) {
                        var implicitInteraction =
                            prevAbstractState.abstractTransitions.find {
                                it.abstractAction == itemAbstractAction
                                        /*&& it.label.prevWindow == abstractTransition.prevWindow*/
                                        && it.dest == currentAbstractState
                            }
                        if (implicitInteraction == null) {
                            // create new explicit interaction
                            implicitInteraction = AbstractTransition(
                                abstractAction = itemAbstractAction,
                                isImplicit = true,
                                /*prevWindow = abstractTransition.prevWindow,*/
                                source = prevAbstractState,
                                dest = currentAbstractState
                            )
                            atuaMF.dstg.add(prevAbstractState, currentAbstractState, implicitInteraction)
                        }
                        prevAbstractState.increaseActionCount2(implicitInteraction!!.abstractAction,false)
                        var coverageIncrease = atuaMF!!.statementMF!!.actionIncreasingCoverageTracking[interaction.actionId.toString()]?.size
                        if (coverageIncrease == null)
                            coverageIncrease = 0
                        implicitInteraction!!.abstractAction.updateMeaningfulScore(
                            interaction,
                            destState,
                            sourceState,
                            coverageIncrease>0,
                            atuamf.randomInteractions.contains(interaction.actionId),
                            atuaMF
                        )
                        //addImplicitAbstractInteraction(currentState, prevAbstractState, currentAbstractState, implicitInteraction, prevprevWindow, edgeCondition)
                    }
                }
            }
            parentWidget = sourceState.widgets.find { it.id == parentWidget!!.parentId }
        }
    }

/*    private fun updateResetAndLaunchTransitions(abstractTransition: AbstractTransition, currentAbstractState: AbstractState) {
        val allAbstractStates = ABSTRACT_STATES
        var resetAppAction: AbstractAction?
        var launchAppAction: AbstractAction?
        if (abstractTransition.abstractAction.actionType == AbstractActionType.RESET_APP) {
            launchAppAction = AbstractAction.getLaunchAction()
            resetAppAction = abstractTransition.abstractAction
        } else {
            launchAppAction = abstractTransition.abstractAction
            resetAppAction = null
        }

        allAbstractStates.forEach { abstractState ->
            updateLaunchTransitions(abstractState, launchAppAction!!, currentAbstractState, abstractTransition)
            if (resetAppAction != null) {
                updateLaunchTransitions(abstractState, resetAppAction, currentAbstractState, abstractTransition)
            }
        }
    }*/

    private fun createImplicitTransitionForOtherAbstractStates(
        prevAbstractState: AbstractState,
        processedStateCount: Int,
        abstractTransition: AbstractTransition
    ) {
        var processedStateCount1 = processedStateCount
        /*if (abstractTransition.source.window == abstractTransition.dest.window)
            return*/
        if (abstractTransition.source.window is Dialog && abstractTransition.dest.window is Activity
            && abstractTransition.source.activity == abstractTransition.dest.activity
        )
        // for the transitions go from a dialog back to an activity
        // we should not create implict transition
            return
        if (abstractTransition.source == abstractTransition.dest
            && abstractTransition.source.isOpeningMenus
            && !abstractTransition.dest.isOpeningMenus
        )
            return
        if (abstractTransition.abstractAction.actionType == AbstractActionType.CLOSE_KEYBOARD)
            return
        val guaranteedAVMs = ArrayList<AttributeValuationMap>()
        val otherSameStaticNodeAbStates = getSimilarAbstractStates(prevAbstractState, abstractTransition)
        val notSoDifferentAbstractStates =
            getSlightlyDifferentAbstractStates(abstractTransition.source, otherSameStaticNodeAbStates)
        addImplicitAbstractTransitionToSameWindowAbstractStates(
            notSoDifferentAbstractStates,
            processedStateCount1,
            abstractTransition,
            guaranteedAVMs
        )
    }

    fun getSlightlyDifferentAbstractStates(
        abstractState: AbstractState,
        otherSameStaticNodeAbStates: List<AbstractState>
    ): ArrayList<AbstractState> {
        val lv1Attributes1 = abstractState.extractGeneralAVMs()
        val notSoDifferentAbstractStates = ArrayList<AbstractState>()
        val overDifferentSet = ArrayList<AbstractState>()
        val threshold = 0.8
        otherSameStaticNodeAbStates.forEach {
            var isSimilar = false
            isSimilar = it.isSimlarAbstractState(lv1Attributes1, threshold)
            if (isSimilar) {
                notSoDifferentAbstractStates.add(it)
            } else {
                overDifferentSet.add(it)
            }
        }
        return notSoDifferentAbstractStates
    }




    private fun addImplicitAbstractTransitionToSameWindowAbstractStates(
        otherSameStaticNodeAbStates: List<AbstractState>,
        processedStateCount1: Int,
        abstractTransition: AbstractTransition,
        guaranteedAVMs: List<AttributeValuationMap>
    ) {
        var processedStateCount11 = processedStateCount1

        otherSameStaticNodeAbStates.forEach {
            processedStateCount11 += 1
            val dest = if (abstractTransition.dest == abstractTransition.source)
                it
            else
                abstractTransition.dest
            /*val exisitingImplicitTransitions = it.abstractTransitions.filter {
                it.abstractAction == abstractTransition.abstractAction
                        && it.modelVersion == ModelVersion.RUNNING
                        && it.isImplicit
                        *//*&& it.prevWindow == abstractTransition.prevWindow*//*
                        && (
                        it.dependentAbstractStates.intersect(abstractTransition.dependentAbstractStates).isNotEmpty()
                                || it.guardEnabled == false
                                || it.dependentAbstractStates.isEmpty()
                        )

                        && (!abstractTransition.abstractAction.isItemAction() ||
                        it.data == abstractTransition.data )
                        *//*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                        || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*//*

            }
            exisitingImplicitTransitions.forEach { abTransition ->
                val edge = atuaMF.dstg.edge(abTransition.source,abTransition.dest,abTransition)
                if (edge != null) {
                    atuaMF.dstg.remove(edge)
                }
                it.abstractTransitions.remove(abTransition)
            }*/
            var exisitingAbstractTransition: AbstractTransition?
            exisitingAbstractTransition = it.abstractTransitions.find {
                it.abstractAction == abstractTransition.abstractAction
                        && it.dest == dest
                        /*&& it.prevWindow == abstractTransition.prevWindow*/
                        && it.interactions.isNotEmpty()
            }
            val guaranteedAVMs =
                abstractTransition.guaranteedRetainedAVMs.filter { avm -> it.attributeValuationMaps.contains(avm) }
            if (exisitingAbstractTransition == null) {
                exisitingAbstractTransition = AbstractTransition(
                    abstractAction = abstractTransition.abstractAction,
                    source = it,
                    dest = dest,
                    /*prevWindow = abstractTransition.prevWindow,*/
                    data = abstractTransition.data,
                    isImplicit = true
                )
                exisitingAbstractTransition.guaranteedRetainedAVMs.clear()
                exisitingAbstractTransition.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
                exisitingAbstractTransition.guaranteedNewAVMs.addAll(abstractTransition.guaranteedNewAVMs)
                if (abstractTransition.dependentAbstractStates.isNotEmpty()) {
                    exisitingAbstractTransition.guardEnabled = abstractTransition.guardEnabled
                    exisitingAbstractTransition.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
                }
                exisitingAbstractTransition.userInputs.addAll(abstractTransition.userInputs)
                // implicitAbstractTransition!!.guaranteedAVMs.addAll(guaranteedAVMs)
                atuaMF.dstg.add(it, dest, exisitingAbstractTransition)
            } else {
                if (abstractTransition.dependentAbstractStates.isNotEmpty()) {
                    exisitingAbstractTransition.guardEnabled = abstractTransition.guardEnabled
                    exisitingAbstractTransition.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
                }
                exisitingAbstractTransition.userInputs.addAll(abstractTransition.userInputs)

                exisitingAbstractTransition.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
                exisitingAbstractTransition.guaranteedNewAVMs.addAll(abstractTransition.guaranteedNewAVMs)
            }
        }
    }

    private fun createImplicitTransitionForVirtualAbstractState(
        abstractTransition: AbstractTransition,
        virtualAbstractState: AbstractState
    ) {
        val abstractAction = abstractTransition.abstractAction
        if (abstractTransition.source.window is Dialog && abstractTransition.dest.window is Activity
            && abstractTransition.source.activity == abstractTransition.dest.activity
        )
        // for the transitions go from a dialog back to an activity
        // we should not create implict transition
            return
        if (abstractTransition.source == abstractTransition.dest
            && abstractTransition.source.isOpeningMenus
            && !abstractTransition.dest.isOpeningMenus
        )
            return
/*        if (abstractTransition.source.window == abstractTransition.dest.window)
            return*/
        if (abstractAction.isWidgetAction() && !virtualAbstractState.attributeValuationMaps.contains(abstractAction.attributeValuationMap!!)) {
            virtualAbstractState.attributeValuationMaps.add(abstractAction.attributeValuationMap!!)
        }
        val guaranteedAVMs = ArrayList<AttributeValuationMap>()
        val existingVirtualTransitions = virtualAbstractState.abstractTransitions.filter {
            it.abstractAction == abstractAction
            /*&& it.prevWindow == abstractTransition.prevWindow*/
            /*&& it.data == abstractTransition.data*/
        }
        if (existingVirtualTransitions.isNotEmpty()) {
            val dests = ArrayList(existingVirtualTransitions.map { it.dest })
            dests.add(abstractTransition.dest)
            val destWindows = dests.map { it.window }.distinct()
            if (destWindows.size > 1) {
                return
            }

            guaranteedAVMs.addAll(dests.map { it.attributeValuationMaps }.reduce { interset, avms ->
                ArrayList(interset.intersect(avms))
            })
            // update guaranteedAVMs for abstract transitions
            existingVirtualTransitions.forEach {
                it.guaranteedRetainedAVMs.clear()
                it.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
            }
        } else {
            guaranteedAVMs.addAll(abstractTransition.dest.attributeValuationMaps)
        }
        val changeEffects: List<ChangeEffect> = computeChanges(abstractTransition)
        val destVirtualAbstractState = ABSTRACT_STATES.find {
            it is VirtualAbstractState && it.window == abstractTransition.dest.window
        }

        val exisitingTransitions = virtualAbstractState.abstractTransitions.filter {
            it.abstractAction == abstractAction
                    /*&& it.prevWindow == abstractTransition.prevWindow*/
                    /*&& it.data == abstractTransition.data
                    && (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                    || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                    && (
                    it.dependentAbstractStates.intersect(abstractTransition.dependentAbstractStates).isNotEmpty()
                            || it.guardEnabled == false
                            || it.dependentAbstractStates.isEmpty()
                    )
        }
        exisitingTransitions.forEach {
            val edge = atuaMF.dstg.edge(it.source, it.dest, it)
            if (edge != null) {
                atuaMF.dstg.remove(edge)
            }
            virtualAbstractState.abstractTransitions.remove(it)
        }

        /*if (destVirtualAbstractState != null) {
            val existingVirtualTransition1 = virtualAbstractState.abstractTransitions.find {
                it.abstractAction == abstractAction
                        && it.dest == destVirtualAbstractState
                        *//*&& it.prevWindow == abstractTransition.prevWindow*//*
                        && it.data == abstractTransition.data
            }
            if (existingVirtualTransition1 == null) {
                val newVirtualTransition = AbstractTransition(
                        abstractAction = abstractAction,
                        source = virtualAbstractState,
                        dest = destVirtualAbstractState,
                        *//*prevWindow = abstractTransition.prevWindow,*//*
                        data = abstractTransition.data,
                        isImplicit = true
                )
                newVirtualTransition.guaranteedAVMs.addAll(guaranteedAVMs)
                newVirtualTransition.changeEffects.addAll(changeEffects)
                virtualAbstractState.abstractTransitions.add(newVirtualTransition)
                newVirtualTransition.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
                atuaMF.dstg.add(virtualAbstractState, destVirtualAbstractState, newVirtualTransition)
            } else {
                existingVirtualTransition1.changeEffects.addAll(changeEffects)
                existingVirtualTransition1.guaranteedAVMs.addAll(guaranteedAVMs)
                existingVirtualTransition1.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
            }
        }*/
        val existingVirtualTransition2 = virtualAbstractState.abstractTransitions.find {
            it.abstractAction == abstractAction
                    && it.dest == abstractTransition.dest
            /*&& it.prevWindow == abstractTransition.prevWindow*/
            /*&& it.data == abstractTransition.data*/
        }
        if (existingVirtualTransition2 == null) {
            val newVirtualTransition = AbstractTransition(
                abstractAction = abstractAction,
                source = virtualAbstractState,
                dest = abstractTransition.dest,
                /*prevWindow = abstractTransition.prevWindow,*/
                data = abstractTransition.data,
                isImplicit = true
            )
            newVirtualTransition.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
            newVirtualTransition.changeEffects.addAll(changeEffects)
            virtualAbstractState.abstractTransitions.add(newVirtualTransition)
            newVirtualTransition.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
            newVirtualTransition.userInputs.addAll(abstractTransition.userInputs)
            atuaMF.dstg.add(virtualAbstractState, abstractTransition.dest, newVirtualTransition)
        } else {
            existingVirtualTransition2.changeEffects.addAll(changeEffects)
            existingVirtualTransition2.dependentAbstractStates.addAll(abstractTransition.dependentAbstractStates)
            existingVirtualTransition2.guaranteedRetainedAVMs.addAll(guaranteedAVMs)
            existingVirtualTransition2.userInputs.addAll(abstractTransition.userInputs)
        }
        /* // get existing action
         var virtualAbstractAction = virtualAbstractState.getAvailableActions().find {
             it == abstractAction
         }


         if (virtualAbstractAction == null) {
             if (abstractAction.attributeValuationMap != null) {
                 val avm = virtualAbstractState.attributeValuationMaps.find {it == abstractAction.attributeValuationMap}
                 if (avm != null) {
                     virtualAbstractAction = AbstractAction(actionType = abstractAction.actionType,
                             attributeValuationMap = avm,
                             extra = abstractAction.extra)

                 } else {
                     val newAttributeValuationSet = abstractAction.attributeValuationMap
                     virtualAbstractState.attributeValuationMaps.add(newAttributeValuationSet)
                     //virtualAbstractState.addAttributeValuationSet(newAttributeValuationSet)
                     virtualAbstractAction = AbstractAction(actionType = abstractAction.actionType,
                             attributeValuationMap = newAttributeValuationSet,
                             extra = abstractAction.extra)
                 }
             } else {
                 virtualAbstractAction = AbstractAction(actionType = abstractAction.actionType,
                         attributeValuationMap = null,
                         extra = abstractAction.extra)
             }
             virtualAbstractState.addAction(virtualAbstractAction)
         }
         if (virtualAbstractAction!=null ) {
             virtualAbstractState.increaseActionCount(virtualAbstractAction)
         }*/
        /* if (isTargetAction) {
             virtualAbstractState.targetActions.add(virtualAbstractAction)
         }
         val implicitDestAbstractState = if (currentAbstractState != prevAbstractState) {
             currentAbstractState
         } else {
             virtualAbstractState
         }
         if (implicitDestAbstractState == virtualAbstractState)
             return
         val abstractEdge = autautMF.abstractTransitionGraph.edges(virtualAbstractState)
                 .filter {
                     it.label.abstractAction == virtualAbstractAction
                             && it.label.prevWindow == prevprevWindow
                             && it.destination?.data == implicitDestAbstractState
                             && it.label.data == abstractTransition.data
                 }
         if (abstractEdge.isEmpty()) {
             val existingAbstractInteraction = autautMF.abstractTransitionGraph.edges(virtualAbstractState).find {
                 it.label.abstractAction == virtualAbstractAction
                         && it.label.prevWindow == prevprevWindow
                         && it.label.data == abstractTransition.data
                         && it.label.dest == implicitDestAbstractState
             }?.label
             val implicitAbstractInteraction = if (existingAbstractInteraction != null) {
                 existingAbstractInteraction
             } else
                 AbstractTransition(
                         abstractAction = virtualAbstractAction,
                         isImplicit = true,
                         data = abstractTransition.data,
                         prevWindow = prevprevWindow,
                         source = virtualAbstractState,
                         dest = implicitDestAbstractState
                 )

             val edge = autautMF.abstractTransitionGraph.add(virtualAbstractState, implicitDestAbstractState, implicitAbstractInteraction)
             if (!edge.label.userInputs.contains(edgeCondition)) {
                 edge.label.userInputs.add(edgeCondition)
             }

         }*/
    }

    private fun computeChanges(abstractTransition: AbstractTransition): List<ChangeEffect> {
        val sourceAbstractState = abstractTransition.source
        val destAbstractState = abstractTransition.dest
        if (sourceAbstractState.rotation != destAbstractState.rotation) {
            val changeResult = ChangeEffect(AffectElementType.Rotation, null, true)
            return listOf(changeResult)
        } else {
            val changeResult = ChangeEffect(AffectElementType.Rotation, null, false)
            return listOf(changeResult)
        }
        return emptyList()
    }

    private fun createImplicitInverseRotationTransition(
        currentAbstractState: AbstractState,
        prevAbstractState: AbstractState
    ) {
        val inverseAbstractAction = currentAbstractState.getAvailableActions().find {
            it.actionType == AbstractActionType.ROTATE_UI
        }
        if (inverseAbstractAction != null) {
            val inverseAbstractInteraction = AbstractTransition(
                abstractAction = inverseAbstractAction,
                /*prevWindow = implicitBackWindow,*/
                isImplicit = true,
                source = currentAbstractState,
                dest = prevAbstractState
            )
            atuaMF.dstg.add(currentAbstractState, prevAbstractState, inverseAbstractInteraction)
            //currentAbstractState.increaseActionCount(inverseAbstractAction)
        }
    }

    private fun createImplictiInverseSwipeTransition(
        abstractTransition: AbstractTransition,
        currentAbstractState: AbstractState,
        prevAbstractState: AbstractState
    ) {
        val swipeDirection = abstractTransition.abstractAction.extra
        var inverseSwipeDirection = if (swipeDirection == "SwipeUp") {
            "SwipeDown"
        } else if (swipeDirection == "SwipeDown") {
            "SwipeUp"
        } else if (swipeDirection == "SwipeLeft") {
            "SwipeRight"
        } else {
            "SwipeLeft"
        }
        val inverseAbstractAction = currentAbstractState.getAvailableActions().find {
            it.actionType == AbstractActionType.SWIPE
                    && it.attributeValuationMap == abstractTransition.abstractAction.attributeValuationMap
                    && it.extra == inverseSwipeDirection
        }
        if (inverseAbstractAction != null) {
            val inverseAbstractInteraction = AbstractTransition(
                abstractAction = inverseAbstractAction,
                data = inverseAbstractAction.extra,
                /*prevWindow = implicitBackWindow,*/
                isImplicit = true,
                source = currentAbstractState,
                dest = prevAbstractState
            )
            atuaMF.dstg.add(currentAbstractState, prevAbstractState, inverseAbstractInteraction)
            currentAbstractState.increaseActionCount2(inverseAbstractAction, false)
        }
    }

    private fun createImplicitBackTransition(
        currentAbstractState: AbstractState,
        prevWindowAbstractState: AbstractState?,
        backAbstractAction: AbstractAction,
        processedStateCount: Int,
        prevAbstractState: AbstractState,
        addedCount: Int
    ) {
        var processedStateCount1 = processedStateCount
        var addedCount1 = addedCount
        if (prevAbstractState == currentAbstractState) {
            return
        }
        if (!currentAbstractState.getAvailableActions().contains(backAbstractAction))
            return
        if (currentAbstractState.abstractTransitions.any {
            it.abstractAction == backAbstractAction
                    && it.interactions.isNotEmpty()
            }){
            return
        }
        var backAbstractState: AbstractState? = prevWindowAbstractState
        if (backAbstractState != null) {
            val existingAT = AbstractTransition.findExistingAbstractTransitions(
                abstractTransitionSet = currentAbstractState.abstractTransitions.toList(),
                abstractAction = backAbstractAction,
                source = currentAbstractState,
                dest = backAbstractState
            )
            if (existingAT == null) {
                val backAbstractInteraction = AbstractTransition(
                    abstractAction = backAbstractAction,
                    isImplicit = true,
                    /*prevWindow = prevprevWindow,*/
                    source = currentAbstractState,
                    dest = backAbstractState!!
                )
                backAbstractInteraction.dependentAbstractStates.add(backAbstractState)
                backAbstractInteraction.guardEnabled = true
                atuaMF.dstg.add(currentAbstractState, backAbstractState, backAbstractInteraction)
                // add edge condition
                addedCount1 += 1
            } else {
                if (!existingAT.dependentAbstractStates.contains(backAbstractState)) {
                    existingAT.dependentAbstractStates.add(backAbstractState)
                }
                existingAT.guardEnabled = true
            }
        }
    }

     fun updateImplicitAppTransitions(prevAbstractState: AbstractState, abstractTransition: AbstractTransition) {
        val exisitingImplicitTransitions = prevAbstractState.abstractTransitions.filter {
            it.abstractAction == abstractTransition.abstractAction
                    /*&& it.prevWindow == abstractTransition.prevWindow*/
                    && (!it.guardEnabled || it.dependentAbstractStates.intersect(abstractTransition.dependentAbstractStates).isNotEmpty())
                    /*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                            || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                    && it.isImplicit
        }
        if (abstractTransition.abstractAction.actionType == AbstractActionType.PRESS_BACK
            && abstractTransition.source.window != abstractTransition.dest.window) {
            if (exisitingImplicitTransitions.isNotEmpty()) {
                exisitingImplicitTransitions.forEach {
                    if (!it.dest.isSimlarAbstractState(abstractTransition.dest,0.8)) {
                        AbstractStateManager.INSTANCE.ignoreImplicitDerivedTransition.add(
                            Triple(
                                it.source.window,
                                it.abstractAction,
                                it.dest.window
                            )
                        )
                    }
                    it.activated = false
                }

            }
        }
        exisitingImplicitTransitions.forEach { abTransition ->
            val edge = atuaMF.dstg.edge(abTransition.source, abTransition.dest, abTransition)
            if (edge != null) {
                atuaMF.dstg.remove(edge)
            }
            prevAbstractState.abstractTransitions.remove(abTransition)
        }
    }
/*    private fun updateLaunchTransitions(abstractState: AbstractState, launchAppAction: AbstractAction, currentAbstractState: AbstractState, abstractTransition: AbstractTransition) {
        val existingEdges = atuaMF.dstg.edges(abstractState).filter {
            it.label.abstractAction == launchAppAction
                    && !it.label.isImplicit
        }
        if (existingEdges.isNotEmpty()) {
            existingEdges.forEach {
                atuaMF.dstg.remove(it)
                it.source.data.abstractTransitions.remove(it.label)
            }
        }
        var implicitAbstractInteraction = AbstractTransition(
                abstractAction = launchAppAction,
                isImplicit = true,
                data = abstractTransition.data,
                *//*prevWindow = null,*//*
                source = abstractState,
                dest = currentAbstractState
        )
        atuaMF.dstg.add(abstractState, currentAbstractState, implicitAbstractInteraction)
    }*/

    // This should not be implicit added to another abstract states
    private fun isSwipeScreenGoToAnotherWindow(
        abstractAction: AbstractAction,
        currentAbstractState: AbstractState,
        prevAbstractState: AbstractState
    ) =
        (abstractAction.actionType == AbstractActionType.SWIPE && abstractAction.attributeValuationMap == null
                && currentAbstractState.window != prevAbstractState.window)

    private fun getOrCreateImplicitAbstractInteraction(
        abstractTransition: AbstractTransition,
        sourceAbstractState: AbstractState,
        destinationAbstractState: AbstractState
    ): AbstractTransition? {
        var implicitAbstractTransition: AbstractTransition?

        if (abstractTransition.abstractAction.attributeValuationMap == null) {
            //find existing interaction again
            val existingEdge = atuaMF.dstg.edges(sourceAbstractState).find {
                it.label.abstractAction == abstractTransition.abstractAction
                        /*&& it.label.prevWindow == abstractTransition.prevWindow*/
                        && it.label.data == abstractTransition.data
                        && it.destination?.data == destinationAbstractState
            }

            if (existingEdge != null) {
                return null
            }
            implicitAbstractTransition =
                AbstractTransition(
                    abstractAction = abstractTransition.abstractAction,
                    isImplicit = true,
                    data = abstractTransition.data,
                    /*prevWindow = abstractTransition.prevWindow,*/
                    source = sourceAbstractState,
                    dest = destinationAbstractState
                )
            implicitAbstractTransition!!.guardEnabled = abstractTransition.guardEnabled
        } else {
            //find Widgetgroup
            val widgetGroup = sourceAbstractState.attributeValuationMaps.find {
                it.equals(abstractTransition.abstractAction.attributeValuationMap)
            }
            if (widgetGroup != null) {
                //find existing interaction again
                val existingEdge = atuaMF.dstg.edges(sourceAbstractState).filter {
                    it.label.abstractAction.equals(abstractTransition.abstractAction)
                            /*&& it.label.prevWindow == abstractTransition.prevWindow*/
                            && it.label.data == abstractTransition.data
                            && destinationAbstractState == it.destination?.data
                }
                if (existingEdge.isNotEmpty()) {
                    return null
                }
                implicitAbstractTransition =
                    AbstractTransition(
                        abstractAction = AbstractAction.getOrCreateAbstractAction(
                            actionType = abstractTransition.abstractAction.actionType,
                            attributeValuationMap = widgetGroup,
                            extra = abstractTransition.abstractAction.extra,
                            window = abstractTransition.source.window
                        ),
                        isImplicit = true,
                        data = abstractTransition.data,
                        /*prevWindow = abstractTransition.prevWindow,*/
                        source = sourceAbstractState,
                        dest = destinationAbstractState
                    )
            } else {
                implicitAbstractTransition = null
            }
            if (implicitAbstractTransition != null) {
                implicitAbstractTransition!!.guardEnabled = abstractTransition.guardEnabled
            }
        }

        return implicitAbstractTransition
    }

    fun getPotentialAbstractStates(): List<AbstractState> {
        return ABSTRACT_STATES.filterNot {
            it is VirtualAbstractState
                    || it.guiStates.isEmpty()
                    || it.window is Launcher
                    || it.window is OutOfApp
                    || it.attributeValuationMaps.isEmpty()
                    || it.ignored
        }
    }

    val usefulUnseenBaseAbstractStates = ArrayList<AbstractState>()
    fun dump(dstgFolder: Path) {
        val resetAbstractState = getAbstractState(launchStates.get(LAUNCH_STATE.RESET_LAUNCH)!!)!!
        File(dstgFolder.resolve("AbstractStateList.csv").toUri()).bufferedWriter().use { all ->
            all.write(header())
            ABSTRACT_STATES.filter { it !is VirtualAbstractState && it.guiStates.isNotEmpty() }.forEach {
                all.newLine()
                val abstractStateInfo = dumpAbstractState(it)
                all.write(abstractStateInfo)
            }
            val unreachedWindow = WindowManager.instance.updatedModelWindows.filter {
                !ABSTRACT_STATES.any {
                    it !is VirtualAbstractState
                            && it.guiStates.isNotEmpty()
                }
            }
            usefulUnseenBaseAbstractStates.addAll(ABSTRACT_STATES.filter {
                it !is VirtualAbstractState
                        && it.window == unreachedWindow
                        && it.modelVersion == ModelVersion.BASE
            })
            val allUnexercisedBaseAbstractTransitions =
                ABSTRACT_STATES.filter { it !is VirtualAbstractState && it.guiStates.isNotEmpty() }
                    .map { it.abstractTransitions }.flatten()
                    .filter {
                        it.interactions.isEmpty()
                                && it.modelVersion == ModelVersion.BASE
                    }

            ABSTRACT_STATES.filter {
                it !is VirtualAbstractState
                        && it.modelVersion == ModelVersion.BASE
                        && it.window != unreachedWindow
                        && !ModelBackwardAdapter.instance.backwardEquivalentAbstractStateMapping.values.flatten()
                    .contains(it)
            }.forEach { abstractState ->
                if (allUnexercisedBaseAbstractTransitions.any { it.dest == abstractState }) {
                    usefulUnseenBaseAbstractStates.add(abstractState)
                }
            }

            usefulUnseenBaseAbstractStates.forEach {
                all.newLine()
                val abstractStateInfo = dumpAbstractState(it)
                all.write(abstractStateInfo)
            }
        }

        val abstractStatesFolder = dstgFolder.resolve("AbstractStates")
        Files.createDirectory(abstractStatesFolder)
        ABSTRACT_STATES.filter { it !is VirtualAbstractState }.forEach {
            it.dump(abstractStatesFolder)
        }

    }

    private fun dumpAbstractState(it: AbstractState): String {
        return "${it.abstractStateId};${it.activity};" +
                "${it.window.windowId};${it.rotation};${it.isOpeningMenus};" +
                "${it.isHomeScreen};${it.isRequestRuntimePermissionDialogBox};" +
                "${it.isAppHasStoppedDialogBox};" +
                "${it.isOutOfApplication};${it.isOpeningKeyboard};" +
                "${it.hasOptionsMenu};" +
                "\"${it.guiStates.map { it.stateId }.joinToString(separator = ";")}\";" +
                "${it.hashCode};${it.isInitalState};${it.modelVersion}"
    }

    private fun header(): String {
        return "[1]abstractStateID;[2]activity;[3]window;[4]rotation;[5]menuOpen;" +
                "[6]isHomeScreen;[7]isRequestRuntimePermissionDialogBox;[8]isAppHasStoppedDialogBox;" +
                "[9]isOutOfApplication;[10]isOpeningKeyboard;[11]hasOptionsMenu;[12]guiStates;[13]hashcode;[14]isInitialState;[15]modelVersion;"
    }

    fun removeObsoleteAbsstractTransitions(correctAbstractTransition: AbstractTransition) {
        val similarAbstractTransitions = getType1SimilarAbstractTransitions(
            correctAbstractTransition.source,
            correctAbstractTransition,
            correctAbstractTransition.userInputs,
            true
        )
        similarAbstractTransitions.removeIf {
            it.interactions.isNotEmpty()
        }
        correctAbstractTransition.source.abstractTransitions.removeIf { similarAbstractTransitions.contains(it) }
        atuaMF.dstg.edges(correctAbstractTransition.source)
            .filter { !correctAbstractTransition.source.abstractTransitions.contains(it.label) }.forEach {
            atuaMF.dstg.remove(it)
        }
    }

    fun getVirtualAbstractState(window: Window): AbstractState? {
        val result = ABSTRACT_STATES.find { it is VirtualAbstractState && it.window == window }
        return result
    }

    fun createAppStack(traceId:Int, transitionId: Int): Stack<AbstractState> {
        val currentTraceId = traceId
        val windowStack = Stack<Window>()
        val appStateStack = Stack<AbstractState>()
        val guiStateStack = Stack<State<*>>()
        // construct gui states stack
        for (i in 1..transitionId) {
            val traveredInteraction = atuaMF.tracingInteractionsMap.get(Pair(traceId, i))
            if (traveredInteraction == null)
                throw Exception()
            if (i == 1) {
                val prevState = atuaMF.stateList.find { it.stateId == traveredInteraction.last().prevState }!!
                guiStateStack.push(prevState)
            }
            val state = atuaMF.stateList.find { it.stateId == traveredInteraction.last().resState }!!
            guiStateStack.push(state)
        }
        for (i in 0..guiStateStack.size-2) {
            val prevState = guiStateStack[i]
            val destState = guiStateStack[i+1]
            val prevAbstractState = getAbstractState(prevState)
            val destAbstractState = getAbstractState(destState)
            if (destAbstractState == null)
                continue
            updateAppStack(windowStack,appStateStack,prevAbstractState,prevState,destAbstractState,destState,false)
        }
        return appStateStack
    }

    fun updateAppStack(
        windowStack: Stack<Window>,
        appStateStack: Stack<AbstractState>,
        prevAbstractState: AbstractState?,
        prevState: State<*>,
        currentAbstractState: AbstractState,
        currentState: State<*>,
        isLaunch: Boolean
    ) {

        if (isLaunch) {
            windowStack.push(Launcher.getOrCreateNode())
            val homeScreenState = atuaMF.stateList.findLast { it.isHomeScreen }
            if (homeScreenState != null) {
//                stateList.add(homeScreenState)
                appStateStack.push(getAbstractState(homeScreenState)!!)
            } else {
                appStateStack.push(AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.window is Launcher }!!)
            }
        } else if (prevAbstractState != null) {
            if (windowStack.contains(currentAbstractState.window)) {
                // Return to the prev window
                // Pop the window
                while (true) {
                    val topWindow = windowStack.pop()
                    if (appStateStack.isNotEmpty()) {
                        while (appStateStack.peek().window != topWindow) {
                            appStateStack.pop()
                            if (appStateStack.isEmpty())
                                break
                        }
                    }
                    if (appStateStack.isNotEmpty()) {
                        while (appStateStack.peek() == currentAbstractState) {
                            appStateStack.pop()
                            if (appStateStack.isEmpty())
                                break
                        }
                    }
                    if (topWindow == currentAbstractState.window)
                        break
                }
            } else {
                if (currentAbstractState.window != prevAbstractState.window) {
                    if (prevAbstractState.window is Activity || prevAbstractState.window is OutOfApp) {

                        windowStack.push(prevAbstractState.window)
                    }
                }
                appStateStack.removeIf {
                    it.isSimlarAbstractState(prevAbstractState,0.8)
                }
                appStateStack.push(prevAbstractState)
            }
        }
    }

    fun getPrevSameWindowAbstractState(
        currentState: State<*>,
        traceId: Int,
        transitionId: Int,
        isReturnToPrevWindow: Boolean
    ): List<AbstractState> {
        val appStateStack = createAppStack(traceId, transitionId)

        val prevSameWindowAbstractStates = ArrayList<AbstractState>()
        val currentAppState = getAbstractState(currentState)!!
        val currentWindow = currentAppState.window
        var ignoreWindow = true
        while (appStateStack.isNotEmpty()) {
            val prevAppState = appStateStack.pop()
            if (prevAppState.window != currentWindow) {
                if (isReturnToPrevWindow)
                    if (ignoreWindow)
                        continue
                    else
                        break
                else
                    break
            } else {
                if (isReturnToPrevWindow)
                    ignoreWindow = false
            }
            if (!prevAppState.isOpeningKeyboard || currentAppState.isOpeningKeyboard)
                prevSameWindowAbstractStates.add(prevAppState)
        }
        return prevSameWindowAbstractStates
    }
    companion object {
        val INSTANCE: AbstractStateManager by lazy {
            AbstractStateManager()
        }
        private val log: org.slf4j.Logger by lazy { LoggerFactory.getLogger(AbstractStateManager::class.java) }
    }
}