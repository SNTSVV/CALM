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

package org.atua.calm

import org.droidmate.exploration.ExplorationContext
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.dstg.DSTG
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.calm.ewtgdiff.AdditionSet
import org.atua.calm.ewtgdiff.EWTGDiff
import org.atua.calm.ewtgdiff.Replacement
import org.atua.calm.ewtgdiff.ReplacementSet
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.AttributeType
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.window.Dialog
import org.droidmate.explorationModel.interaction.State
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ModelBackwardAdapter {
    val backwardEquivalentAbstractStateMapping = HashMap<AbstractState, HashSet<AbstractState>>()
    val backwardEquivalentAbstractTransitionMapping = HashMap<AbstractTransition, HashSet<AbstractTransition>>()
    val observedBaseAbstractState = HashSet<AbstractState>()
    val incorrectTransitions = HashSet<AbstractTransition>()
    val observedBasedAbstractTransitions = HashSet<AbstractTransition>()
    val initialBaseAbstractTransitions = HashSet<AbstractTransition>()
    val keptBaseAbstractTransitions = HashSet<AbstractTransition>()
    val initialBaseAbstractStates = HashSet<AbstractState>()
    val keptBaseAbstractStates = HashSet<AbstractState>()
    val entierlyNewAbstractStates = HashSet<AbstractState>()
    val entierlyNewGuiStates = HashSet<State<*>>()
    val quasibackwardEquivalentMapping = HashMap<Pair<AbstractState, AbstractAction>,HashSet<Pair<AbstractState,AbstractState>>>()
    val checkingQuasibackwardEquivalent = Stack<Triple<AbstractState,AbstractAction,AbstractState>> ()
    val backwardEquivalentByGUIState = HashMap<State<*>, HashSet<AbstractState>>()
    val ALL_BASE_APPSTATES: List<AbstractState> by lazy {
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.modelVersion == ModelVersion.BASE }
    }
    fun runtimeAdaptation() {
    }

    fun checkingEquivalence(guiState: State<*>, observedAbstractState: AbstractState, observedAbstractTransition: AbstractTransition, prevWindowAbstractState: AbstractState?, atuamf: ATUAMF) {
        if (!isConsideringAbstractAction(observedAbstractTransition.abstractAction)) {
            return
        }
        val obsoleteBaseAbstractTransitions =
            getObsoletReusedAbstractTransitions(observedAbstractTransition, prevWindowAbstractState)

        obsoleteBaseAbstractTransitions.forEach {
            observedAbstractTransition.source.abstractTransitions.remove(it)
            atuamf.dstg.removeAbstractActionEnabiblity(it,atuamf)
        }

        obsoleteBaseAbstractTransitions.forEach {
            val edge = atuamf.dstg.edge(it.source,it.dest,it)
            if (edge!=null)
                atuamf.dstg.remove(edge)
        }
        if (observedAbstractState.isHomeScreen)
            return
        /*if (abstractTransition.abstractAction.actionType == AbstractActionType.RESET_APP) {
            checkingQuasibackwardEquivalent.clear()
        }*/
        if (entierlyNewAbstractStates.contains(observedAbstractState)) {
            backwardEquivalentByGUIState.putIfAbsent(guiState,HashSet())
            return
        }
        val candidates = ALL_BASE_APPSTATES.filter {
            (!backwardEquivalentByGUIState.contains(guiState))
                    && it !is VirtualAbstractState
                    && (it.window == observedAbstractState.window
                        || (it.window is Dialog && observedAbstractState.window is Dialog
                            && (it.window as Dialog).ownerActivitys
                            .intersect(
                                (observedAbstractState.window as Dialog).ownerActivitys
                            ).isNotEmpty()))
                    && it.rotation == observedAbstractState.rotation
                    && it.isOpeningMenus == observedAbstractState.isOpeningMenus
                    && it.isOpeningKeyboard == observedAbstractState.isOpeningKeyboard
        }
        backwardEquivalentByGUIState.putIfAbsent(guiState,HashSet())

        val baseAVMsByUpdatedAVMsMatching = HashMap<AttributeValuationMap,ArrayList<AttributeValuationMap>>() // observered - expected
        val updatedAVMsByBaseAVMsMatching = HashMap<AttributeValuationMap,ArrayList<AttributeValuationMap>>() // expected - observered
        val unmatchedAVMs1 = ArrayList<AttributeValuationMap>()
        val unmatchedAVMs2 = ArrayList<AttributeValuationMap>()
        if (observedAbstractTransition.modelVersion == ModelVersion.BASE) {
            observedBasedAbstractTransitions.add(observedAbstractTransition)
        }
        if (backwardEquivalentAbstractStateMapping.containsKey(observedAbstractState)) {
            backwardEquivalentByGUIState[guiState]!!.add(observedAbstractState)
            // need verify reused abstract transitions
            return
        }
        if (observedAbstractState.modelVersion == ModelVersion.BASE) {
            observedBaseAbstractState.add(observedAbstractState)
            /*if (isBackwardEquivant(observedAbstractState, observedAbstractState,matchedAVMs1,matchedAVMs2,unmatchedAVMs1,unmatchedAVMs2, false)) {
                registerBackwardEquivalence(guiState, observedAbstractState, observedAbstractState, atuamf, matchedAVMs2,candidates)
            }*/
            return
        }
        if (entierlyNewGuiStates.contains(guiState))
            return
        var backwardEquivalenceFound = false
        if (observedAbstractTransition.abstractAction.isLaunchOrReset()) {
            val baseModelInitialStates = ALL_BASE_APPSTATES.filter {
                it.isInitalState && it.modelVersion == ModelVersion.BASE
            }
            baseModelInitialStates.forEach {baseModelInitialState->
                val expectedAbstractState = baseModelInitialState
                baseAVMsByUpdatedAVMsMatching.clear()
                updatedAVMsByBaseAVMsMatching.clear()
                unmatchedAVMs1.clear()
                unmatchedAVMs2.clear()
                if (isBackwardEquivant(observedAbstractState, expectedAbstractState,baseAVMsByUpdatedAVMsMatching,updatedAVMsByBaseAVMsMatching,unmatchedAVMs1,unmatchedAVMs2, false,atuamf)) {
                    registerBackwardEquivalence(guiState, observedAbstractState, expectedAbstractState, atuamf, updatedAVMsByBaseAVMsMatching,candidates)
                    backwardEquivalenceFound = true
                } else {
                    // report unmatchedAVMs
                }
            }
            if (backwardEquivalenceFound) {
                return
            }
           /* if (expectedAbstractState.window == observedAbstractState.window) {
                checkingQuasibackwardEquivalent.push(Triple(expectedAbstractState,abstractTransition.abstractAction ,observedAbstractState))
            }*/
        }
        if(!backwardEquivalenceFound) {
            if (obsoleteBaseAbstractTransitions.isNotEmpty()) {
                obsoleteBaseAbstractTransitions.forEach {
                    val expected = it.dest
                    baseAVMsByUpdatedAVMsMatching.clear()
                    updatedAVMsByBaseAVMsMatching.clear()
                    unmatchedAVMs1.clear()
                    unmatchedAVMs2.clear()
                    if (observedAbstractState == expected) {
                        backwardEquivalenceFound = true
                    } else if (isBackwardEquivant(observedAbstractState, expected,baseAVMsByUpdatedAVMsMatching,updatedAVMsByBaseAVMsMatching,unmatchedAVMs1,unmatchedAVMs2, false,atuamf)) {
                        registerBackwardEquivalence(guiState, observedAbstractState, expected, atuamf, updatedAVMsByBaseAVMsMatching,candidates)
                        backwardEquivalenceFound = true
                        backwardEquivalentAbstractTransitionMapping.putIfAbsent(observedAbstractTransition,HashSet())
                        backwardEquivalentAbstractTransitionMapping.get(observedAbstractTransition)!!.add(it)
                    } else {
                        observedAbstractTransition.source.abstractTransitions.remove(it)
                        atuamf.dstg.removeAbstractActionEnabiblity(it,atuamf)
                        incorrectTransitions.add(it)
                        /*backwardEquivalentAbstractTransitionMapping.get(it)?.forEach {
                            it.source.abstractTransitions.remove(it)
                        }*/
                        backwardEquivalentAbstractTransitionMapping.get(it)?.forEach {
                            incorrectTransitions.add(it)
                        }
                    }
                }
            }
            if (!backwardEquivalenceFound) {
                candidates.filter { backwardEquivalentByGUIState.get(guiState)!!.contains(it)
                        || backwardEquivalentByGUIState.get(guiState)!!.isEmpty()  } .forEach {
                    baseAVMsByUpdatedAVMsMatching.clear()
                    updatedAVMsByBaseAVMsMatching.clear()
                    unmatchedAVMs1.clear()
                    unmatchedAVMs2.clear()
                    if (isBackwardEquivant(observedAbstractState, it, baseAVMsByUpdatedAVMsMatching, updatedAVMsByBaseAVMsMatching,unmatchedAVMs1,unmatchedAVMs2,  true,atuamf)) {
                        registerBackwardEquivalence(guiState, observedAbstractState, it, atuamf, updatedAVMsByBaseAVMsMatching,candidates)
                        backwardEquivalenceFound = true
                    }
                }
            }
            if (backwardEquivalenceFound) {
                return
            } else {
                entierlyNewAbstractStates.add(observedAbstractState)
                entierlyNewGuiStates.add(guiState)
                return
            }
           /* var continueCheckingQuasibackwardEquivalent = false
            baseAbstractTransitions.forEach {
                val dest = it.dest
                if (dest.window == observedAbstractState.window) {
                    checkingQuasibackwardEquivalent.push(Triple(dest,abstractTransition.abstractAction ,observedAbstractState))
                    continueCheckingQuasibackwardEquivalent = true
                }
            }*/
        }
    }

    private fun getObsoletReusedAbstractTransitions(
        abstractTransition: AbstractTransition,
        prevWindowAbstractState: AbstractState?
    ): List<AbstractTransition> {
        val obsoleteBaseAbstractTransitions = ArrayList<AbstractTransition>()
        val sameActionBaseAbstractTransitions = abstractTransition.source.abstractTransitions.filter {
            it.activated &&
            it.abstractAction == abstractTransition.abstractAction
                    && it.modelVersion == ModelVersion.BASE
                    && it.interactions.isEmpty()
        }
        val reliableTransitions = sameActionBaseAbstractTransitions.filter {
            it.activated &&
            (!it.guardEnabled || it.dependentAbstractStates.isEmpty()
                    || (prevWindowAbstractState != null &&
                    (it.dependentAbstractStates.contains(prevWindowAbstractState)
                            || it.dependentAbstractStates.any {
                        it == backwardEquivalentAbstractStateMapping.get(
                            prevWindowAbstractState
                        )
                    })))
        }
        if (reliableTransitions.isNotEmpty())
            obsoleteBaseAbstractTransitions.addAll(reliableTransitions)
        else
            obsoleteBaseAbstractTransitions.addAll(sameActionBaseAbstractTransitions)
        return obsoleteBaseAbstractTransitions
    }

    private fun registerBackwardEquivalence(guiState: State<*>,
                                            observedAbstractState: AbstractState,
                                            expectedAbstractState: AbstractState,
                                            atuamf: ATUAMF,
                                            updatedAVMsByBaseAVMsMatching: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
                                            otherSimilarAbstractStates: List<AbstractState>) {
        // isBackwardEquivant(observedAbstractState,expected, HashMap(),HashMap(),false)
        backwardEquivalentByGUIState.putIfAbsent(guiState,HashSet())
        backwardEquivalentByGUIState[guiState]!!.add(expectedAbstractState)
        backwardEquivalentAbstractStateMapping.putIfAbsent(observedAbstractState, HashSet())
        backwardEquivalentAbstractStateMapping[observedAbstractState]!!.add(expectedAbstractState)
        matchingInputs(updatedAVMsByBaseAVMsMatching, expectedAbstractState, observedAbstractState, atuamf)
        copyAbstractTransitions(observedAbstractState, expectedAbstractState, atuamf, updatedAVMsByBaseAVMsMatching,false)
        ALL_BASE_APPSTATES.subtract(observedBaseAbstractState).forEach { appState ->
            val abstractTransitions = appState.abstractTransitions.filter { it.dest == expectedAbstractState }
            abstractTransitions.forEach { at ->
                val newAbstractTransition = AbstractTransition(
                    source = appState,
                    dest = observedAbstractState,
                    modelVersion = ModelVersion.BASE,
                    isImplicit = at.isImplicit,
                    abstractAction = at.abstractAction,
                    data = at.data,
                    fromWTG = at.fromWTG,
                    interactions = at.interactions
                )
                if (at.dest.ignored == false)
                    atuamf.dstg.updateAbstractActionEnability(newAbstractTransition,atuamf)
                newAbstractTransition.copyPotentialInfoFrom(at)
                appState.abstractTransitions.remove(at)
                atuamf.dstg.removeAbstractActionEnabiblity(at,atuamf)
            }

        }
        /*otherSimilarAbstractStates.filter { it!=expected }.forEach {
            copyAbstractTransitions(observedAbstractState,it,atuamf,matchedAVMs2,true)
        }*/

        /*AbstractStateManager.INSTANCE.ABSTRACT_STATES.remove(expected)*/
    }

    private fun matchingInputs(
        updatedAVMsByBaseAVMsMatching: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        expectedAbstractState: AbstractState,
        observedAbstractState: AbstractState,
        atuamf: ATUAMF
    ) {
        if (expectedAbstractState.window != observedAbstractState.window) {
            val updatedInputs = observedAbstractState.window.inputs.filter { it.widget == null }
            expectedAbstractState.window.inputs.filter { it.widget == null }.forEach { baseInput ->
                val updatedInput = updatedInputs.find {
                    it.eventType == baseInput.eventType
                            && it.data == baseInput.data
                }
                if (updatedInput != null) {
                    updateInputBasedOnBaseInput(baseInput, updatedInput, atuamf)
                } else {
                    log.warn("Cannot finnd corresponding updated input")
                }
            }
        }
        updatedAVMsByBaseAVMsMatching.forEach { baseAVM, updatedAVMs ->
            val baseWidget = expectedAbstractState.EWTGWidgetMapping[baseAVM]
            if (baseWidget != null) {
                val baseInputs = expectedAbstractState.window.inputs.filter { it.widget == baseWidget }
                updatedAVMs.forEach { updatedAVM ->
                    val updatedWidget = observedAbstractState.EWTGWidgetMapping[updatedAVM]
                    if (updatedWidget != null && updatedWidget != baseWidget) {
                        val updatedInputs = observedAbstractState.window.inputs.filter { it.widget == updatedWidget }
                        baseInputs.forEach { baseInput ->
                            val updatedInput = updatedInputs.find {
                                it.eventType == baseInput.eventType
                                        && it.data == baseInput.data
                            }
                            if (updatedInput != null) {
                                updateInputBasedOnBaseInput(baseInput, updatedInput, atuamf)
                            } else {
                                log.warn("Cannot finnd corresponding updated input")
                            }
                        }
                    } else if (updatedWidget == null) {
                        log.warn("Cannot find updated widget corresponding to updated AVM")
                    }
                }
            } else {
                log.warn("Cannot find updated widget corresponding to base AVM")
            }
        }
    }

    private fun updateInputBasedOnBaseInput(
        baseInput: Input,
        updatedInput: Input,
        atuamf: ATUAMF
    ) {
        baseInput.modifiedMethods.forEach {
            updatedInput.modifiedMethods.putIfAbsent(it.key, false)
        }
        if ((updatedInput.eventHandlers.isNotEmpty()
                    && updatedInput.eventHandlers.intersect(atuamf.allTargetHandlers).isNotEmpty())
            || (updatedInput.modifiedMethods.isNotEmpty())
        ) {
            if (!TargetInputReport.INSTANCE.targetIdentifiedByStaticAnalysis.contains(updatedInput)) {
                TargetInputReport.INSTANCE.targetIdentifiedByBaseModel.add(updatedInput)
            }
        }
        if (updatedInput.modifiedMethods.isNotEmpty() &&
            !updatedInput.modifiedMethods.all { atuamf.statementMF!!.fullyCoveredMethods.contains(it.key) }
        ) {
            if (!atuamf.notFullyExercisedTargetInputs.contains(updatedInput)) {
                atuamf.notFullyExercisedTargetInputs.add(updatedInput)
            }
        }
    }

    private fun isConsideringAbstractAction(abstractAction: AbstractAction): Boolean {
        val result = when (abstractAction.actionType) {
            AbstractActionType.WAIT -> true
            AbstractActionType.PRESS_HOME -> false
            else -> true
        }
        return result
    }

    private fun copyAbstractTransitions(destination: AbstractState,
                                        source: AbstractState, atuamf: ATUAMF,
                                        sourceDestAVMMatching: Map<AttributeValuationMap,List<AttributeValuationMap>>,
                                        copyAsImplicit: Boolean) {
        source.abstractTransitions.filter {
            it.modelVersion == ModelVersion.BASE
                    && it.activated
                    /*&& !incorrectTransitions.contains(it)*/
                    && it.abstractAction.isWidgetAction()
                    && it.abstractAction.actionType != AbstractActionType.SWIPE
                    && it.source != it.dest
        }. forEach { sourceTransition->
            if (!sourceTransition.abstractAction.isWidgetAction()) {
                val sourceAbstractAction = sourceTransition.abstractAction
                val updatedAVMs = sourceDestAVMMatching.get(sourceAbstractAction.attributeValuationMap)
                var destAbstractAction: AbstractAction
                destAbstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = sourceAbstractAction.actionType,
                    extra = sourceAbstractAction.extra,
                    window = destination.window
                )
                var destInputs = destination.getInputsByAbstractAction(destAbstractAction)
                if (destInputs.isEmpty()) {
                    destInputs = destination.window.inputs.filter {
                        it.widget == null && it.eventType == Input.getEventTypeFromActionName(destAbstractAction.actionType)
                    }
                    destInputs.forEach {
                        destination.associateAbstractActionWithInputs(destAbstractAction,it)
                    }
                }
                copyAbstractTransitionFromBase(
                    sourceTransition,
                    destination,
                    destAbstractAction!!,
                    atuamf.dstg,
                    atuamf,
                    false
                )
            } else {
                val destAVMs = sourceDestAVMMatching.get(sourceTransition.abstractAction.attributeValuationMap!!)
                if (destAVMs!=null) {
                    destAVMs.forEach {destAVM->
                        if (isActionValidOnAVM(sourceTransition.abstractAction.actionType,destAVM)) {
                            val destAbstractAction = AbstractAction.getOrCreateAbstractAction(
                                actionType = sourceTransition.abstractAction.actionType,
                                attributeValuationMap = destAVM,
                                extra = sourceTransition.abstractAction.extra,
                                window = destination.window
                            )
                            var destInputs = destination.getInputsByAbstractAction(destAbstractAction)
                            if (destInputs.isEmpty()) {
                                val destWidget = destination.EWTGWidgetMapping[destAVM]
                                if (destWidget != null) {
                                    destInputs = destination.window.inputs.filter {
                                        it.widget == destWidget
                                                && it.eventType == Input.getEventTypeFromActionName(destAbstractAction.actionType)
                                    }
                                    destInputs.forEach {
                                        destination.associateAbstractActionWithInputs(destAbstractAction,it)
                                    }
                                }
                            }
                            copyAbstractTransitionFromBase(
                                sourceTransition,
                                destination,
                                destAbstractAction,
                                atuamf.dstg,
                                atuamf,
                                copyAsImplicit
                            )
                        }

                    }
                }
            }
        }


    }

    private fun isActionValidOnAVM(actionType: AbstractActionType, destAVM: AttributeValuationMap): Boolean {
        val isValid = when (actionType) {
            AbstractActionType.CLICK -> destAVM.isClickable()
            AbstractActionType.LONGCLICK -> destAVM.isLongClickable()
            AbstractActionType.SWIPE -> destAVM.isScrollable()
            AbstractActionType.ITEM_CLICK, AbstractActionType.ITEM_LONGCLICK -> true
            else -> false
        }
        return isValid
    }

    private fun copyAbstractTransitionFromBase(
        baseTransition: AbstractTransition,
        updatedAbstractState: AbstractState,
        updatedAbstractAction: AbstractAction,
        dstg: DSTG,
        atuamf: ATUAMF,
        copyAsImplicit: Boolean
    ) {
        val dependendAbstractStates = ArrayList<AbstractState>()
        for (dependentAbstractState in baseTransition.dependentAbstractStates) {
            if (dependentAbstractState.guiStates.isNotEmpty())
                dependendAbstractStates.add(dependentAbstractState)
            else {
                val equivalences = backwardEquivalentAbstractStateMapping.filter { it.value.contains(dependentAbstractState) }
                equivalences.forEach { t, _ ->
                    dependendAbstractStates.add(t)
                }
            }
        }
        if (dependendAbstractStates.isEmpty() && baseTransition.dependentAbstractStates.isNotEmpty()) {
            return
        }
        val dest = if (baseTransition.dest.guiStates.isNotEmpty())
            baseTransition.dest
        else
            backwardEquivalentAbstractStateMapping.get(baseTransition.dest)?.firstOrNull() ?: baseTransition.dest

        if (dependendAbstractStates.contains(dest)) {
            AbstractStateManager.INSTANCE.goBackAbstractActions.add(updatedAbstractAction)
            val inputs = updatedAbstractState.getInputsByAbstractAction(updatedAbstractAction)
            inputs.forEach {
                if (!Input.goBackInputs.contains(it))
                    Input.goBackInputs.add(it)
            }
        }
        if (copyAsImplicit && AbstractStateManager.INSTANCE.ignoreImplicitDerivedTransition.contains(Triple(updatedAbstractState.window,updatedAbstractAction,dest.window))){
            return
        }
        if (copyAsImplicit && AbstractStateManager.INSTANCE.goBackAbstractActions.contains(updatedAbstractAction)) {
            return
        }
        if (!baseTransition.abstractAction.isWebViewAction()) {
            if (!copyAsImplicit) {
                val existingUpdatedAbstractTransition = updatedAbstractState.abstractTransitions.find {
                    it.modelVersion == ModelVersion.RUNNING
                            && it.interactions.isNotEmpty()
                            && it.abstractAction == updatedAbstractAction
                            && (it.guardEnabled == false
                            || it.dependentAbstractStates.isEmpty()
                            || dependendAbstractStates.isEmpty()
                            || it.dependentAbstractStates.intersect(dependendAbstractStates).isNotEmpty())
                            && it.fromWTG == false
                }
                if (existingUpdatedAbstractTransition != null) {
                    return
                }
            } else {
                val existingUpdatedAbstractTransition = updatedAbstractState.abstractTransitions.find {
                    it.modelVersion == ModelVersion.RUNNING
                            && it.abstractAction == updatedAbstractAction
                            && (it.guardEnabled == false
                            || it.dependentAbstractStates.isEmpty()
                            || dependendAbstractStates.isEmpty()
                            || it.dependentAbstractStates.intersect(dependendAbstractStates).isNotEmpty())
                            && it.fromWTG == false
                }
                if (existingUpdatedAbstractTransition != null) {
                    return
                }
            }
        }
        val existingAbstractTransition = updatedAbstractState.abstractTransitions.find {
            it.modelVersion == ModelVersion.BASE
                    && it.abstractAction == updatedAbstractAction
                    && it.dest == dest
                    /*&& it.prevWindow == sourceTransition.prevWindow*/

            /* && it.dependentAbstractState == dependentAbstractState*/
        }

        if (existingAbstractTransition == null) {
            // create new Abstract Transition
            val newAbstractTransition = AbstractTransition(
                    source = updatedAbstractState,
                    dest = baseTransition.dest,
                    /*prevWindow = sourceTransition.prevWindow,*/
                    abstractAction = updatedAbstractAction,
                    isImplicit = copyAsImplicit,
                    modelVersion = ModelVersion.BASE,
                    data = baseTransition.data
            )
            // updatedAbstractState.increaseActionCount2(updatedAbstractAction,false)
            if (dependendAbstractStates.isNotEmpty()) {
                newAbstractTransition.dependentAbstractStates.addAll(dependendAbstractStates)
                newAbstractTransition.guardEnabled = baseTransition.guardEnabled
            }
            dstg.add(newAbstractTransition.source, newAbstractTransition.dest, newAbstractTransition)
            newAbstractTransition.userInputs.addAll(baseTransition.userInputs)
            if (newAbstractTransition.source != newAbstractTransition.dest
                && newAbstractTransition.dest.ignored == false)
                atuamf.dstg.updateAbstractActionEnability(newAbstractTransition,atuamf)
            baseTransition.handlers.forEach { handler, _ ->
                newAbstractTransition.handlers.putIfAbsent(handler, false)
            }
            val possiblyCoveredUpdatedMethods = baseTransition.methodCoverage.filter { atuamf.statementMF!!.isModifiedMethod(it) }
            newAbstractTransition.modifiedMethods.putAll(possiblyCoveredUpdatedMethods.associateWith { false })
            val inputs = updatedAbstractState.getInputsByAbstractAction(updatedAbstractAction)
            backwardEquivalentAbstractTransitionMapping.put(newAbstractTransition, HashSet())
            backwardEquivalentAbstractTransitionMapping[newAbstractTransition]!!.add(baseTransition)
        } else {
            // copy additional information
            if (dependendAbstractStates.isNotEmpty()) {
                existingAbstractTransition.guardEnabled = baseTransition.guardEnabled
                existingAbstractTransition.dependentAbstractStates.addAll(dependendAbstractStates)
            }
            val possiblyCoveredUpdatedMethods = baseTransition.methodCoverage.filter { atuamf.statementMF!!.isModifiedMethod(it) }
            existingAbstractTransition.modifiedMethods.putAll(possiblyCoveredUpdatedMethods.associateWith { false })
            val inputs = updatedAbstractState.getInputsByAbstractAction(updatedAbstractAction)
            backwardEquivalentAbstractTransitionMapping.putIfAbsent(existingAbstractTransition, HashSet())
            backwardEquivalentAbstractTransitionMapping[existingAbstractTransition]!!.add(baseTransition)
        }
    }

    private fun isBackwardEquivant(observedAbstractState: AbstractState,
                                   expectedAbstractState: AbstractState,
                                   matchedAVMs1: HashMap<AttributeValuationMap,ArrayList<AttributeValuationMap>>,
                                   matchedAVMs2: HashMap<AttributeValuationMap,ArrayList<AttributeValuationMap>>,
                                   unmatchedAVMs1: ArrayList<AttributeValuationMap>,
                                   unmatchedAVMs2: ArrayList<AttributeValuationMap>,
                                   strict: Boolean,
    atuaMF: ATUAMF): Boolean {
        if (observedAbstractState.window != expectedAbstractState.window
            && observedAbstractState.window !is Dialog
            && observedAbstractState.window !is Dialog)
            return false
        if (observedAbstractState.isOpeningKeyboard != expectedAbstractState.isOpeningKeyboard)
            return false
        if (observedAbstractState.isOpeningMenus != expectedAbstractState.isOpeningMenus)
            return false
        if (observedAbstractState.rotation != expectedAbstractState.rotation)
            return false
        val addedAVMS = ArrayList<AttributeValuationMap>()
        val addedWidgets = EWTGDiff.instance.widgetDifferentSets.get("AdditionSet") as AdditionSet?
        matchingAVMs(
            observedAbstractState,
            addedWidgets?.addedElements?: emptyList(),
            addedAVMS,
            expectedAbstractState,
            matchedAVMs1,
            matchedAVMs2,
            unmatchedAVMs1,
            unmatchedAVMs2,
            atuaMF
        )
        if (unmatchedAVMs1.size == 0 && unmatchedAVMs2.size == 0) {
            return true
        }
        unmatchedAVMs1.removeIf { it.getResourceId().isBlank() }
        unmatchedAVMs2.removeIf { it.getResourceId().isBlank() }
        if (unmatchedAVMs1.size == 0 && unmatchedAVMs2.size == 0) {
            return true
        }
        val unmatchedWidgets1 =  unmatchedAVMs1.map { observedAbstractState.EWTGWidgetMapping.get(it)}.filter { it!=null }.distinct()
        val unmatchedWidgets2 = unmatchedAVMs2.map { expectedAbstractState.EWTGWidgetMapping.get(it) }.filter{it !=null}.distinct()
        if (!strict) {
            if (unmatchedWidgets1.intersect(unmatchedWidgets2).isEmpty())
                return true
        }

        /*val baseAbstractStateToAttributeValuationMaps = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
            it.window == observedAbstractState.window
                    && it.modelVersion == ModelVersion.BASE
        }. map { it to it.attributeValuationMaps }*/
        val updateWindowCreatedRuntimeWidgets = observedAbstractState.window.widgets
            .filter { (it.createdAtRuntime ) &&  it.modelVersion == ModelVersion.RUNNING }
        val baseWindowCreatedRuntimeWidgets = expectedAbstractState.window.widgets
            .filter { (it.createdAtRuntime  )&&  it.modelVersion == ModelVersion.BASE }
        val condition1 = if (unmatchedWidgets1.isNotEmpty()) unmatchedWidgets1.all {
            updateWindowCreatedRuntimeWidgets.contains(it)
                    && !baseWindowCreatedRuntimeWidgets.contains(it)} else true
        val condition2 = if (unmatchedWidgets2.isNotEmpty()) unmatchedWidgets2.all {
            baseWindowCreatedRuntimeWidgets.contains(it)
                    && !updateWindowCreatedRuntimeWidgets.contains(it)} else true
        if (condition1 && condition2) {
            return true
        }

        /*// we consider only avm having abstractTransitions associated to
        unmatchedAVMs1.removeIf { avm -> !observedAbstractState.abstractTransitions.any { it.abstractAction.attributeValuationMap == avm  }}
        unmatchedAVMs2.removeIf { avm -> !expectedAbstractState.abstractTransitions.any { it.abstractAction.attributeValuationMap == avm } }
        val unmatchedWidgets1_2 =  ArrayList(unmatchedAVMs1.map {  observedAbstractState.EWTGWidgetMapping.get(it)})
        val unmatchedWidgets2_2 = ArrayList(unmatchedAVMs2.map { expectedAbstractState.EWTGWidgetMapping.get(it) })
        unmatchedWidgets1_2.removeIf { it == null }
        unmatchedWidgets2_2.removeIf { it == null }
        val condition1_2 = if (unmatchedWidgets1_2.isNotEmpty()) unmatchedWidgets1_2.all {
            updateWindowCreatedRuntimeWidgets.contains(it)
                    && !baseWindowCreatedRuntimeWidgets.contains(it)} else true
        val condition2_2 = if (unmatchedWidgets2_2.isNotEmpty()) unmatchedWidgets2_2.all {
            baseWindowCreatedRuntimeWidgets.contains(it)
                    && !updateWindowCreatedRuntimeWidgets.contains(it)} else true
        if (condition1_2 && condition2_2) {
            return true
        }*/
        return false
    }

    private fun matchingAVMs(
        observedAbstractState: AbstractState,
        addedWidgets: List<EWTGWidget>,
        addedAVMS: ArrayList<AttributeValuationMap>,
        expectedAbstractState: AbstractState,
        matchedAVMs1: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        matchedAVMs2: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        unmatchedAVMs1: ArrayList<AttributeValuationMap>,
        unmatchedAVMs2: ArrayList<AttributeValuationMap>,
        atuaMF: ATUAMF
    ) {
        observedAbstractState.EWTGWidgetMapping.forEach { avm, widget ->
            if (addedWidgets.contains(widget)) {
                addedAVMS.add(avm)
            }
        }
        phase1MatchingAVMs(
            observedAbstractState,
            addedAVMS,
            expectedAbstractState,
            matchedAVMs1,
            matchedAVMs2,
            unmatchedAVMs1,
            unmatchedAVMs2
        )
        phase2MatchingAVMs(observedAbstractState,expectedAbstractState, unmatchedAVMs1, unmatchedAVMs2, matchedAVMs1, matchedAVMs2)
        phase3MatchingAVMs(observedAbstractState,expectedAbstractState, unmatchedAVMs1, unmatchedAVMs2, matchedAVMs1, matchedAVMs2, atuaMF)
    }

    // This phase is reserved for matching specifically the EWTGWidget not detected by GATOR (i.e., the EWTGWidget created at runtime)
    private fun phase3MatchingAVMs(
        observedAbstractState: AbstractState,
        expectedAbstractState: AbstractState,
        unmatchedAVMs1: ArrayList<AttributeValuationMap>,
        unmatchedAVMs2: ArrayList<AttributeValuationMap>,
        matchedAVMs1: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        matchedAVMs2: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        atuaMF: ATUAMF
    ) {
        val refinedUnmatchedAVMs1 = unmatchedAVMs1.filter { observedAbstractState.EWTGWidgetMapping[it]?.createdAtRuntime?:false }
         val refinedUnmatchedAVMs2 = unmatchedAVMs2.filter { expectedAbstractState.EWTGWidgetMapping[it]?.createdAtRuntime?:false }
        refinedUnmatchedAVMs1.forEach { avm1 ->
            val newEWTGWidget = observedAbstractState.EWTGWidgetMapping[avm1]!!

            // Consider only different EWTGWidget
            val matches = refinedUnmatchedAVMs2.filterNot{ avm2 -> expectedAbstractState.EWTGWidgetMapping[avm2] == newEWTGWidget }
                .filter { avm2 -> isEquivalentAttributeValuationMaps(avm1, avm2) }
            if (matches.isNotEmpty()) {

                matchedAVMs1.putIfAbsent(avm1, ArrayList())
                val matchedList = matchedAVMs1.get(avm1)!!
                matches.forEach {
                    if (!matchedList.contains(it))
                        matchedList.add(it)
                    matchedAVMs2.putIfAbsent(it, ArrayList())
                    if (!matchedAVMs2.get(it)!!.contains(avm1))
                        matchedAVMs2.get(it)!!.add(avm1)
                    if (EWTGDiff.instance.widgetDifferentSets.get("ReplacementSet") != null) {
                        val oldEWTGWidget = expectedAbstractState.EWTGWidgetMapping[it]!!
                        if (oldEWTGWidget != newEWTGWidget) {
                            val replacement = Replacement<EWTGWidget>(oldEWTGWidget,newEWTGWidget)
                            val replacementSet = (EWTGDiff.instance.widgetDifferentSets.get("ReplacementSet")!! as ReplacementSet<EWTGWidget>)
                            if (!replacementSet.replacedElements.contains(replacement)) {
                                replacementSet.replacedElements.add(replacement)
                                EWTGDiff.instance.updateReplacementInput(replacement, true, atuaMF)
                            }
                        }

                    }


                }
            }
        }
        unmatchedAVMs1.removeIf { matchedAVMs1.containsKey(it) }
        unmatchedAVMs2.removeIf { matchedAVMs2.containsKey(it) }
        unmatchedAVMs2.forEach { avm2 ->
            val matches = unmatchedAVMs1.filter { avm1 -> isEquivalentAttributeValuationMaps(avm2, avm1) }
            if (matches.isNotEmpty()) {
                matchedAVMs2.putIfAbsent(avm2, ArrayList())
                val matchedList = matchedAVMs2.get(avm2)!!
                matches.forEach {
                    if (!matchedList.contains(it))
                        matchedList.add(it)
                    matchedAVMs1.putIfAbsent(it, ArrayList())
                    if (!matchedAVMs1.get(it)!!.contains(avm2))
                        matchedAVMs1.get(it)!!.add(avm2)
                }
            }
        }
        unmatchedAVMs1.removeIf { matchedAVMs1.containsKey(it) }
        unmatchedAVMs2.removeIf { matchedAVMs2.containsKey(it) }
    }

    private fun phase2MatchingAVMs(
        observedAbstractState: AbstractState,
        expectedAbstractState: AbstractState,
        unmatchedAVMs1: ArrayList<AttributeValuationMap>,
        unmatchedAVMs2: ArrayList<AttributeValuationMap>,
        matchedAVMs1: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        matchedAVMs2: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>
    ) {
        val replacedWidgets = EWTGDiff.instance.getReplacingWidget()
        unmatchedAVMs1.forEach { avm1 ->
            val associatedWidget = observedAbstractState.EWTGWidgetMapping.get(avm1)
            if (replacedWidgets.contains(associatedWidget)) {
                val matches =
                    expectedAbstractState.EWTGWidgetMapping.filter { it.value == associatedWidget }.keys.filter {
                        it.isClickable() == avm1.isClickable()
                                /*&& it.isLongClickable() == avm1.isLongClickable()*/
                                && it.isChecked() == avm1.isChecked()
                        /*&& it.isScrollable() == avm1.isScrollable()*/
                    }
                if (matches.isNotEmpty()) {
                    matchedAVMs1.putIfAbsent(avm1, ArrayList())
                    val matchedList = matchedAVMs1.get(avm1)!!
                    matches.forEach {
                        if (!matchedList.contains(it))
                            matchedList.add(it)
                        matchedAVMs2.putIfAbsent(it, ArrayList())
                        if (!matchedAVMs2.get(it)!!.contains(avm1))
                            matchedAVMs2.get(it)!!.add(avm1)
                    }
                }
            }
        }
        unmatchedAVMs1.removeIf { matchedAVMs1.containsKey(it) }
        unmatchedAVMs2.removeIf { matchedAVMs2.containsKey(it) }

    }

    private fun phase1MatchingAVMs(
        observedAbstractState: AbstractState,
        addedAVMS: ArrayList<AttributeValuationMap>,
        expectedAbstractState: AbstractState,
        matchedAVMs1: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        matchedAVMs2: HashMap<AttributeValuationMap, ArrayList<AttributeValuationMap>>,
        unmatchedAVMs1: ArrayList<AttributeValuationMap>,
        unmatchedAVMs2: ArrayList<AttributeValuationMap>
    ) {
        observedAbstractState.attributeValuationMaps.filterNot { addedAVMS.contains(it) }.forEach { avm1 ->
            var matchedAVMs = expectedAbstractState.attributeValuationMaps.filter { it == avm1 || it.hashCode == avm1.hashCode}

            if (matchedAVMs.isNotEmpty()) {
                matchedAVMs1.putIfAbsent(avm1, ArrayList())
                val matchedList = matchedAVMs1.get(avm1)!!
                matchedAVMs.forEach {
                    if (!matchedList.contains(it))
                        matchedList.add(it)
                    matchedAVMs2.putIfAbsent(it, ArrayList())
                    val matchedList2 = matchedAVMs2.get(it)!!
                    if (!matchedList2.contains(avm1))
                        matchedList2.add(avm1)
                }
            } else {
                unmatchedAVMs1.add(avm1)
            }
        }
        expectedAbstractState.attributeValuationMaps.filterNot { matchedAVMs2.keys.contains(it) }.forEach { avm2 ->
            var matchedAVMs = observedAbstractState.attributeValuationMaps.filter { it == avm2 || it.hashCode == avm2.hashCode }
            if (matchedAVMs.isNotEmpty()) {
                matchedAVMs2.putIfAbsent(avm2, ArrayList())
                val matchedList = matchedAVMs2.get(avm2)!!
                matchedAVMs.forEach {
                    if (!matchedList.contains(it))
                        matchedList.add(it)
                    matchedAVMs1.putIfAbsent(it, ArrayList())
                    val matchedList2 = matchedAVMs1.get(it)!!
                    if (!matchedList2.contains(avm2))
                        matchedList2.add(avm2)
                }
            } else {
                unmatchedAVMs2.add(avm2)
            }
        }
        val window = observedAbstractState.window
        unmatchedAVMs1.forEach { avm1 ->
            var matchedAVMs = unmatchedAVMs2.filter { avm2 ->
                avm1.isDerivedFrom(avm2, window) || avm2.isDerivedFrom(avm1,window)
            }
            if (matchedAVMs.isNotEmpty()) {
                matchedAVMs1.putIfAbsent(avm1, ArrayList())
                val matchedList = matchedAVMs1.get(avm1)!!
                matchedAVMs.forEach { avm2 ->
                    if (!matchedList.contains(avm2))
                        matchedList.add(avm2)
                    matchedAVMs2.putIfAbsent(avm2, ArrayList())
                    val matchedList2 = matchedAVMs2.get(avm2)!!
                    if (!matchedList2.contains(avm1))
                        matchedList2.add(avm1)
                    unmatchedAVMs2.remove(avm2)
                }
            }
        }
        unmatchedAVMs1.removeAll(matchedAVMs1.keys)
    }

    private fun isEquivalentAttributeValuationMaps(avm1: AttributeValuationMap, avm2: AttributeValuationMap): Boolean {
        val scoreDetails = HashMap<AttributeType,Float>()
        avm2.localAttributes.forEach {
            val score = when (it.key) {
                AttributeType.resourceId -> if ( it.value == "" && avm1.localAttributes[it.key]!! == "" )
                        Float.NaN
                    else if (it.value == "" && avm1.localAttributes[it.key]!! != "")
                        -1.0f
                    else if (it.value != "" && avm1.localAttributes[it.key]!! == "")
                        -1.0f
                    else
                        StringComparison.compareStringsLevenshtein(Helper.getUnqualifiedResourceId1(it.value) , Helper.getUnqualifiedResourceId1(avm1.localAttributes[it.key]!!))
//                    StringComparison.compareStringsSimple(Helper.getUnqualifiedResourceId1(it.value) , Helper.getUnqualifiedResourceId1(avm1.localAttributes[it.key]!!))
                AttributeType.xpath -> {
//                    val d1 = StringComparison.compareStringsXpathConsine(it.value,avm1.localAttributes[it.key]!!)
//                    val d2 = StringComparison.compareStringsXpathDice(it.value,avm1.localAttributes[it.key]!!)
//                    val d3 = StringComparison.compareStringsXpathJacard(it.value,avm1.localAttributes[it.key]!!)
//                    val d4 = StringComparison.compareStringsXpathCommonLongestSubsequence(it.value,avm1.localAttributes[it.key]!!)
//                    val min = arrayListOf<Float>(d1,d4).min()!!
//                    StringComparison.compareStringsSimple(it.value,avm1.localAttributes[it.key]!!)
                    StringComparison.compareStringsXpathLevenshtein(it.value,avm1.localAttributes[it.key]!!)
                }

                /*AttributeType.clickable -> if (it.value == avm1.localAttributes[it.key])
                    1.0f
                else
                    0.0f*/
                else -> Float.NaN
            }
            if (!score.isNaN())
                scoreDetails.put(it.key,score)
            /*if (!avm1.localAttributes.containsKey(it.key))
                return false
            if (avm1.localAttributes[it.key] != it.value)
                return  false*/
        }/*
        if (scoreDetails.any { it.value == 0.0f })
            return false*/
        if (scoreDetails.any { it.value == -1.0f})
            return false
        if (scoreDetails.any { it.value == 1.0f } && scoreDetails.all { it.value > 0.4f })
            return true
        if (scoreDetails.any { it.value > 0.95f } && scoreDetails.all { it.value > 0.4f })
            return true
       return false
    }

    fun outputBackwardEquivalentResult(actionId: Int, guiState: State<*>, expectedAbstractState: AbstractState, observedAbstractState: AbstractState){

    }
     fun produceReport(context: ExplorationContext<*,*,*>) {
        backwardEquivalentAbstractStateMapping.entries.removeIf{
            it.key.guiStates.isEmpty()
        }
        backwardEquivalentAbstractTransitionMapping.entries.removeIf {
            it.key.source.guiStates.isEmpty()
        }
         val sb2 = StringBuilder()

            val keptBaseAbstractStates = instance.keptBaseAbstractStates
            val initialBaseAbstractStates = instance.initialBaseAbstractStates
            sb2.appendln("Initial base astract states;${initialBaseAbstractStates.size}")
            sb2.appendln("Kept base astract states;${keptBaseAbstractStates.size}")
            val newlyCreatedAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                it.modelVersion == ModelVersion.RUNNING
                        && it !is VirtualAbstractState
                        && it.guiStates.isNotEmpty()

            }
            val testedWindowsInBaseModel = initialBaseAbstractStates.map { it.window }.distinct()
            val newAbstractStatesOfUntestedWindowsInBaseModel = newlyCreatedAbstractStates.filter { !testedWindowsInBaseModel.contains(it.window) }
            sb2.appendln("Total new astract states;${newlyCreatedAbstractStates.size}")
            newlyCreatedAbstractStates.forEach {
                sb2.appendln(it.abstractStateId)
            }
            sb2.appendln("New abstract states of untested windows in base model;${newAbstractStatesOfUntestedWindowsInBaseModel.size}")
            val reusedAbstractState = instance.observedBaseAbstractState
            sb2.appendln("Observered abstract state;${reusedAbstractState.size}")
            reusedAbstractState.forEach {
                sb2.appendln(it.abstractStateId)
            }
            sb2.appendln("Backward equivalence identification;${instance.backwardEquivalentAbstractStateMapping.size}")
            instance.backwardEquivalentAbstractStateMapping.forEach { newState, baseStates ->
                sb2.appendln("---")
                sb2.appendln("New state;${newState.abstractStateId}")
                sb2.appendln("Base states count;${baseStates.size}")
                baseStates.forEach {
                    sb2.appendln(it.abstractStateId)
                }
            }
            val initialBaseAbstractTransitions = instance.initialBaseAbstractTransitions
            sb2.appendln("Initial base abstract transitions;${initialBaseAbstractTransitions.size}")
            val keptBaseAbstractTransitions = instance.keptBaseAbstractTransitions
            sb2.appendln("Kept base abstract transitions;${keptBaseAbstractTransitions.size}")
            val transferedAbstractTransitions = AbstractStateManager.INSTANCE.ABSTRACT_STATES
                    .filter { it.modelVersion == ModelVersion.RUNNING }
                    .flatMap { it.abstractTransitions }
                    .filter { it.isExplicit() && it.modelVersion == ModelVersion.BASE }
            sb2.appendln("Transfered abstract transitions;${transferedAbstractTransitions.size}")
            val newAbstractTransitions =  AbstractStateManager.INSTANCE.ABSTRACT_STATES
                    .flatMap { it.abstractTransitions }
                    .filter { it.interactions.isNotEmpty() }
            sb2.appendln("Total executed abstract transitions;${newAbstractTransitions.size}")
            val newATsBaseToBaseAS = newAbstractTransitions.filter {
                (it.source.modelVersion == ModelVersion.BASE
                        || backwardEquivalentAbstractStateMapping.containsKey(it.source))
                        && (it.dest.modelVersion == ModelVersion.BASE
                        || backwardEquivalentAbstractStateMapping.contains(it.dest))}
            val newATsBaseToNewAs = newAbstractTransitions.filter {
                (it.source.modelVersion == ModelVersion.BASE
                        || backwardEquivalentAbstractStateMapping.containsKey(it.source))
                        && (it.dest.modelVersion == ModelVersion.RUNNING
                        && !backwardEquivalentAbstractStateMapping.contains(it.dest))}
            val newATsNewToBaseAS = newAbstractTransitions.filter {
                (it.source.modelVersion == ModelVersion.RUNNING
                        && !backwardEquivalentAbstractStateMapping.containsKey(it.source))
                        && (it.dest.modelVersion == ModelVersion.BASE
                        || backwardEquivalentAbstractStateMapping.contains(it.dest))}
            val newATsNewToNewAs = newAbstractTransitions.filter {
                (it.source.modelVersion == ModelVersion.RUNNING
                        && !backwardEquivalentAbstractStateMapping.containsKey(it.source))
                        && (it.dest.modelVersion == ModelVersion.RUNNING
                        && !backwardEquivalentAbstractStateMapping.contains(it.dest))}
            sb2.appendln("Base-Base abstract transitions;${newATsBaseToBaseAS.size}")
            sb2.appendln("Base-New abstract transitions;${newATsBaseToNewAs.size}")
            sb2.appendln("New-Base abstract transitions;${newATsNewToBaseAS.size}")
            sb2.appendln("New-New abstract transitions;${newATsNewToNewAs.size}")
            val observedAbstractTransitions = observedBasedAbstractTransitions

            sb2.appendln("Observed abstract transitions count;${observedAbstractTransitions.size}")
            observedAbstractTransitions.forEach {
                sb2.appendln("${it.source.abstractStateId};${it.dest.abstractStateId};" +
                        "${it.abstractAction.actionType};${it.abstractAction.attributeValuationMap?.avmId};${it.data};" /*+
                    "${it.prevWindow}"*/)
            }
            val correctAbstractTransitions = instance.backwardEquivalentAbstractTransitionMapping
            sb2.appendln("Backward Equivalent abstract transitions count;${correctAbstractTransitions.size}")
            /*correctAbstractTransitions.forEach {
                sb2.appendln("${it.key.source.abstractStateId};${it.dest.abstractStateId};" +
                        "${it.abstractAction.actionType};${it.abstractAction.attributeValuationMap?.avmId};${it.data};" *//*+
                    "${it.prevWindow}"*//*)
            }*/
            val incorrectAbstractTransitions = instance.incorrectTransitions
            sb2.appendln("Incorrect abstract transitions count;${incorrectAbstractTransitions.size}")
            incorrectAbstractTransitions.forEach {
                sb2.appendln("${it.source.abstractStateId};${it.dest.abstractStateId};" +
                        "${it.abstractAction.actionType};${it.abstractAction.attributeValuationMap?.avmId};${it.data};"/* +
                    "${it.prevWindow?.windowId}"*/)
            }
            sb2.appendln("Incorrect webview abstract transitions count;${incorrectAbstractTransitions.filter { it.abstractAction.isWebViewAction() }.size}")
            val modelbackwardReport = context.model.config.baseDir.resolve("backwardEquivalenceReport.txt")
            org.atua.modelFeatures.ATUAMF.log.info("Prepare writing backward equivalence report file: " +
                    "\n- File name: ${modelbackwardReport.fileName}" +
                    "\n- Absolute path: ${modelbackwardReport.toAbsolutePath().fileName}")

            Files.write(modelbackwardReport, sb2.lines())
            org.atua.modelFeatures.ATUAMF.log.info("Finished writing report in ${modelbackwardReport.fileName}")
    }
    companion object {
        val instance: ModelBackwardAdapter by lazy {
            ModelBackwardAdapter()
        }
        private val log: org.slf4j.Logger by lazy { LoggerFactory.getLogger(ModelBackwardAdapter::class.java) }
    }
}