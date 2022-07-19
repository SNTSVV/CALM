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

import org.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.window.Activity
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class AbstractTransition(
        var  abstractAction: AbstractAction,
        val interactions: HashSet<Interaction<*>> = HashSet(),
        var isImplicit: Boolean/*,
        var prevWindow: Window?*/,
        var data: Any? =null,
        var fromWTG: Boolean = false,
        val source: AbstractState,
        val dest: AbstractState,
        val modelVersion: ModelVersion = ModelVersion.RUNNING
) {

    var requireWaitAction: Boolean = false
    val guaranteedRetainedAVMs = ArrayList<AttributeValuationMap>() // guaranteedAVMsInDest
    val guaranteedNewAVMs = ArrayList<AttributeValuationMap>()
    val modifiedMethods = HashMap<String,Boolean>() //method id,
    val modifiedMethodStatement = HashMap<String, Boolean>() //statement id,
    val handlers = HashMap<String,Boolean>() // handler method id
    val tracing = HashSet<Pair<Int,Int>>() // list of traceId-transitionId
    val statementCoverage = HashSet<String>()
    val methodCoverage = HashSet<String>()
    val changeEffects = HashSet<ChangeEffect>()
    // ----------Guard
    val userInputs = HashSet<HashMap<UUID,String>>()
    val inputGUIStates = HashSet<ConcreteId>()
    var dependentAbstractStates = HashSet<AbstractState>()
    var requiringPermissionRequestTransition: AbstractTransition? = null

    var guardEnabled: Boolean = false
    var activated: Boolean = true
        set(value) {
            if (value == false) {
                if (this.nondeterministic || this.interactions.isNotEmpty()) {
                    disableAbstractTransitions.put(this, 50)
                } else {
                    this.source.abstractTransitions.remove(this)
                }
            } else {
                disableAbstractTransitions.remove(this)
            }
            field = value
        }

    var ignored: Boolean = false
    var isUsefullOnce: Boolean = true
    var nondeterministic: Boolean = false
    var nondeterministicCount: Int = 0
    // --------------
    init {
        source.abstractTransitions.add(this)
    }

    /**
     * This function should be called only dependentAbstractState is added.
     */
    fun computeGuaranteedAVMs() {
      return
        if (!isImplicit) {
            if ((source.window == dest.window
                && source.isOpeningMenus == dest.isOpeningMenus)
                || (source.window != dest.window
                        && !dependentAbstractStates.any { it.window == dest.window })) {
                val retainingAVMs = dest.attributeValuationMaps.intersect(source.attributeValuationMaps)
                val sourceAbstractStateEWTGWidgets = source.EWTGWidgetMapping.values
                val newAVMs =
                    dest.EWTGWidgetMapping.filter { !sourceAbstractStateEWTGWidgets.contains(it.value) }.map { it.key }
                guaranteedRetainedAVMs.addAll(retainingAVMs)
                guaranteedNewAVMs.addAll(newAVMs)
            }
            else {
                val retainingAVms = ArrayList<AttributeValuationMap>()
                val newAVMs = ArrayList<AttributeValuationMap>()
                dependentAbstractStates.filter { it !is VirtualAbstractState
                        && it.window == dest.window
                        && it.isOpeningMenus == dest.isOpeningMenus}. forEach { sourceAbstractState ->
                    val retainingElements = dest.attributeValuationMaps.intersect(sourceAbstractState.attributeValuationMaps)
                    if (retainingAVms.isEmpty()) {
                        retainingAVms.addAll(retainingElements)
                    } else {
                        val retains = retainingAVms.intersect(retainingElements)
                        retainingAVms.clear()
                        retainingAVms.addAll(retains)
                    }
                    val sourceAbstractStateEWTGWidgets = sourceAbstractState.EWTGWidgetMapping.values
                    val newElements = dest.EWTGWidgetMapping.filter { !sourceAbstractStateEWTGWidgets.contains(it.value) }.map { it.key }
                    if (newAVMs.isEmpty()) {
                        newAVMs.addAll(newElements)
                    } else {
                        val news = newAVMs.intersect(newElements)
                        newAVMs.clear()
                        newAVMs.addAll(news)
                    }
                }
                guaranteedRetainedAVMs.addAll(retainingAVms)
                guaranteedNewAVMs.addAll(newAVMs)
            }

        }
    }
    fun isExplicit() = !isImplicit

    fun updateUpdateStatementCoverage(statement: String, atuaMF: org.atua.modelFeatures.ATUAMF) {
        val methodId = atuaMF.statementMF!!.statementMethodInstrumentationMap.get(statement)
        if (atuaMF.statementMF!!.isModifiedMethodStatement(statement)) {
            this.modifiedMethodStatement.put(statement, true)
            if (methodId != null) {
                atuaMF.allModifiedMethod.put(methodId,true)
                this.modifiedMethods.put(methodId, true)
            }
        }
        statementCoverage.add(statement)
        methodCoverage.add(methodId!!)
        // update Handler
        if (atuaMF.allEventHandlers.contains(methodId) ) {
            if (handlers.containsKey(methodId)) {
                handlers[methodId] = true
            } else {
                handlers.put(methodId, true)
            }
        }
    }

    fun copyPotentialInfoFrom(other: AbstractTransition) {
        this.dependentAbstractStates.addAll(other.dependentAbstractStates)
        this.guardEnabled = guardEnabled
        this.userInputs.addAll(other.userInputs)
        this.tracing.addAll(other.tracing)
        this.handlers.putAll(other.handlers)
        this.modifiedMethods.putAll(other.modifiedMethods)
        this.modifiedMethodStatement.putAll(other.modifiedMethodStatement)
        this.methodCoverage.addAll(other.methodCoverage)
        this.statementCoverage.addAll(other.statementCoverage)
        this.activated = other.activated
        this.ignored = other.ignored
        this.computeGuaranteedAVMs()
    }

    fun computeMemoryBasedGuards2(currentState: State<*>,
                                 traceId: Int,
                                 transitionId: Int,
                                 atuaMF: ATUAMF
    ) {
        val currentAbstractState = this.dest
        if (currentAbstractState.attributeValuationMaps.isEmpty())
            return
        if (currentAbstractState.isOpeningMenus == false) {
            val recentSameWindowAbStates = AbstractStateManager.INSTANCE.getLatestStates(traceId,transitionId-2,currentAbstractState.window)
            for (recentSameWindowAbState in recentSameWindowAbStates) {
                if (recentSameWindowAbState.isSimlarAbstractState(currentAbstractState,0.8)) {
                    this.dependentAbstractStates.add(recentSameWindowAbState)
                    this.dest.window.guardNeeded = true
                }
            }
        }
    }
    
    fun computeMemoryBasedGuards(currentState: State<*>,
                                 traceId: Int,
                                 transitionId: Int,
                                 atuaMF: ATUAMF
    ) {
        val currentAbstractState = this.dest
        if (currentAbstractState.attributeValuationMaps.isEmpty())
            return
        /*val p_prevWindowAbstractState = if (transitionId != 0) {
            atuaMF.getPrevWindowAbstractState(traceId, transitionId )
        } else
            null*/
        if (/*p_prevWindowAbstractState != null
            &&*/ !currentAbstractState.isOpeningMenus
        ) {
            val currentStateStack = AbstractStateManager.INSTANCE.createAppStack(traceId, transitionId-2)
            val previousSameWindowAbstractStates: List<AbstractState> =
                AbstractStateManager.INSTANCE.getPrevSameWindowAbstractState(currentState, currentStateStack , true)
                    /*.subtract(listOf(this.source))*/.toList()
            var foundPreviousAbstractStates = false
            if (previousSameWindowAbstractStates.isNotEmpty()) {
                for (prevAppState in previousSameWindowAbstractStates) {
                    if (!AbstractStateManager.INSTANCE.goBackAbstractActions.contains(this.abstractAction)) {
                        val inputs =
                            this.source.getInputsByAbstractAction(this.abstractAction)
                        inputs.forEach {
                            if (!Input.goBackInputs.contains(it))
                                Input.goBackInputs.add(it)
                        }
                    } else
                        AbstractStateManager.INSTANCE.goBackAbstractActions.add(this.abstractAction)
                    if (prevAppState.isSimlarAbstractState(currentAbstractState, 0.8)
                    ) {

                        this.guardEnabled = true
                        this.dependentAbstractStates.add(prevAppState)
                        atuaMF.disablePrevAbstractStates.putIfAbsent(abstractAction, HashMap())
                        atuaMF.disablePrevAbstractStates[abstractAction]!!.putIfAbsent(prevAppState, HashSet())
                        atuaMF.disablePrevAbstractStates[abstractAction]!![prevAppState]!!.addAll(currentStateStack.subtract(
                            listOf(prevAppState)))
                        foundPreviousAbstractStates = true
                        break
                    }
                }
                if (!foundPreviousAbstractStates && previousSameWindowAbstractStates.isNotEmpty()) {
                    val notSourceStatePrevWindowAppStates = previousSameWindowAbstractStates
                    if (notSourceStatePrevWindowAppStates.isNotEmpty()) {
                        val similarScores =
                            notSourceStatePrevWindowAppStates.associateWith { it.similarScore(currentAbstractState) }
                        val maxScores = similarScores.maxByOrNull { it.value }!!
                        if (maxScores.value>0.1) {
                            this.guardEnabled = true
                            this.dependentAbstractStates.add(maxScores.key)
                            atuaMF.disablePrevAbstractStates.putIfAbsent(abstractAction, HashMap())
                            atuaMF.disablePrevAbstractStates[abstractAction]!!.putIfAbsent(maxScores.key, HashSet())
                            atuaMF.disablePrevAbstractStates[abstractAction]!![maxScores.key]!!.addAll(
                                currentStateStack.subtract(
                                    listOf(maxScores.key)
                                )
                            )
                        } else {
                            println("It seems that no previous states are similar to the resulting state.")
                        }
                    }
                }
            } else {
                if (this.dest.window.guardNeeded) {
                    computeMemoryBasedGuards2(currentState,traceId,transitionId,atuaMF)
                }
            }
           /* if (currentAbstractState.window == p_prevWindowAbstractState.window) {
            }
            else if (!this.dest.isSimlarAbstractState(this.source,0.8)) {
                val previousSameWindowAbstractStates: List<AbstractState> =
                    AbstractStateManager.INSTANCE.getPrevSameWindowAbstractState(currentState, traceId, transitionId, false)
                for (prevAppState in previousSameWindowAbstractStates) {
                    if (prevAppState.isSimlarAbstractState(currentAbstractState, 0.8)) {
                       *//* if (!AbstractStateManager.INSTANCE.goBackAbstractActions.contains(this.abstractAction)) {
                            val inputs =
                                this.source.getInputsByAbstractAction(this.abstractAction)
                            inputs.forEach {
                                if (!Input.goBackInputs.contains(it))
                                    Input.goBackInputs.add(it)
                            }
                        } else
                            AbstractStateManager.INSTANCE.goBackAbstractActions.add(this.abstractAction)*//*
                        this.guardEnabled = true
                        this.dependentAbstractStates.add(prevAppState)
                        break
                    }
                }
            }*/
        }
    }

    fun markNondeterministicTransitions(atuaMF: ATUAMF) {
        val explicitTransitions =if (this.modelVersion == ModelVersion.RUNNING || this.interactions.isNotEmpty()) {
            this.source.abstractTransitions.filter { it.interactions.isNotEmpty() }
        } else {
            this.source.abstractTransitions.filter { it.modelVersion == ModelVersion.BASE && it.isExplicit()}
        }
        val nondeterministicTransitions = getNonDeterministicTransitions(explicitTransitions)
        if (nondeterministicTransitions.isNotEmpty()) {
            nondeterministicTransitions.forEach { abTransition ->
                if (abTransition.dependentAbstractStates.isEmpty()) {
                    guardTransitionWithSpecialMemory(abTransition, atuaMF)
                }
            }
            guardTransitionWithSpecialMemory(this,atuaMF)
            val nondeterminisiticTransitions2 = getNonDeterministicTransitions(explicitTransitions)
            if (nondeterminisiticTransitions2.isNotEmpty()) {
                this.nondeterministic = true
                this.nondeterministicCount = nondeterminisiticTransitions2.size+1
                nondeterminisiticTransitions2.forEach {
                    it.nondeterministic = true
                    it.nondeterministicCount = nondeterminisiticTransitions2.size+1
                    it.activated = false
                }
            }
        }
    }

    private fun guardTransitionWithSpecialMemory(
        abTransition: AbstractTransition,
        atuaMF: ATUAMF
    ) {
        abTransition.interactions.forEach { interaction ->
            val trace = atuaMF.interactionsTracingMap[listOf(interaction)]
            val destState = atuaMF.stateList.find { interaction.resState == it.stateId }!!
            if (trace != null) {
                computeMemoryBasedGuards2(destState, trace.first, trace.second, atuaMF)
            }
        }
    }

    private fun getNonDeterministicTransitions(explicitTransitions: List<AbstractTransition>): List<AbstractTransition> {
        val nondeterministicTransitions = explicitTransitions.filter {
            it != this
                    && it.abstractAction == this.abstractAction
                    && it.userInputs.intersect(this.userInputs).isNotEmpty()
                    && (it.dependentAbstractStates.isEmpty()
                    || this.dependentAbstractStates.isEmpty()
                    || it.dependentAbstractStates.intersect(this.dependentAbstractStates).isNotEmpty())
        }
        return nondeterministicTransitions
    }

    companion object{
        val interaction_AbstractTransitionMapping = HashMap<Interaction<Widget>,AbstractTransition>()
        
        val disableAbstractTransitions = HashMap<AbstractTransition, Int>()
        fun updateDisableTransitions() {
            disableAbstractTransitions.keys.forEach {
                disableAbstractTransitions.replace(it, disableAbstractTransitions[it]!!-1)
            }
            val toActivateTransitions = disableAbstractTransitions.filter { it.value == 0 }.keys
            toActivateTransitions.forEach {
                it.activated = true
            }
        }
        fun computeAbstractTransitionData(actionType: AbstractActionType, interaction: Interaction<Widget>, guiState: State<Widget>, abstractState: AbstractState, atuaMF: org.atua.modelFeatures.ATUAMF): Any? {
            if (actionType == AbstractActionType.RANDOM_KEYBOARD) {
                return interaction.targetWidget
            }
            if (actionType == AbstractActionType.TEXT_INSERT) {
                if (interaction.targetWidget==null)
                    return null
                val avm = abstractState.getAttributeValuationSet(interaction.targetWidget!!,guiState,atuaMF)
                if (avm!=null) {
                    return interaction.data
                }
                return null
            }
            if (actionType == AbstractActionType.SEND_INTENT)
                return interaction.data
            if (interaction.targetWidget!=null && Helper.hasParentWithType(interaction.targetWidget!!,guiState,"WebView")) {
                return interaction.targetWidget!!.nlpText
            }
            if (actionType != AbstractActionType.SWIPE) {
                return null
            }
            return interaction.data
        }


        fun findExistingAbstractTransitions(abstractTransitionSet: List<AbstractTransition>,
                                                     abstractAction: AbstractAction,
                                                     source: AbstractState,
                                                     dest: AbstractState): AbstractTransition? {
            var existingAbstractTransition: AbstractTransition? = null
          /*  if (prevWindowAbstractState!=null)
                    existingAbstractTransition = abstractTransitionSet.find {
                        it.abstractAction == abstractAction
                                && it.isImplicit == isImplicit
                                && it.data == interactionData
                                && it.source == source
                                && it.dest == dest
                                && it.dependentAbstractStates.contains(prevWindowAbstractState)
                    }
            if (existingAbstractTransition!=null)
                return existingAbstractTransition
            existingAbstractTransition = abstractTransitionSet.find {
                it.abstractAction == abstractAction
                        && it.isImplicit == isImplicit
                        && it.data == interactionData
                        && it.source == source
                        && it.dest == dest
            }
            if (existingAbstractTransition!=null)
                return existingAbstractTransition*/
            existingAbstractTransition = abstractTransitionSet.find {
                it.abstractAction == abstractAction
                        && it.source == source
                        && it.dest == dest
            }
            return existingAbstractTransition
        }
    }
}