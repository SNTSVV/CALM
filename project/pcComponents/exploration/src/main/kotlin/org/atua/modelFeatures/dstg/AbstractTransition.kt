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

import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
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
    var isUsefullOnce: Boolean = true
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
        this.computeGuaranteedAVMs()
    }


    fun updateDependentAppState(currentState: State<*>,
                                traceId: Int,
                                transitionId: Int,
                                atuaMF: ATUAMF
    ) {
        val currentAbstractState = this.dest
        val p_prevWindowAbstractState = if (transitionId != 0) {
            atuaMF.getPrevWindowAbstractState(traceId, transitionId - 1)
        } else
            null
        if (p_prevWindowAbstractState != null
            && !currentAbstractState.isOpeningMenus
            && this.dest != this.source
        ) {
            if (currentAbstractState.window == p_prevWindowAbstractState.window) {
                val previousSameWindowAbstractStates: List<AbstractState> =
                    AbstractStateManager.INSTANCE.getPrevSameWindowAbstractState(currentState, traceId, transitionId, true)
                var foundPreviousAbstractStates = false
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
                        foundPreviousAbstractStates = true
                        break
                    }
                }
                if (!foundPreviousAbstractStates && previousSameWindowAbstractStates.isNotEmpty()) {
                    val notSourceStatePrevWindowAppStates = previousSameWindowAbstractStates.filterNot { it == source }
                    if (notSourceStatePrevWindowAppStates.isNotEmpty()) {
                        val similarScores =
                            notSourceStatePrevWindowAppStates   .associateWith { it.similarScore(currentAbstractState) }
                        val maxScores = similarScores.maxByOrNull { it.value }!!

                        this.guardEnabled = true
                        this.dependentAbstractStates.add(maxScores.key)
                    }
                }
            }
            else if (!this.dest.isSimlarAbstractState(this.source,0.8)) {
                val previousSameWindowAbstractStates: List<AbstractState> =
                    AbstractStateManager.INSTANCE.getPrevSameWindowAbstractState(currentState, traceId, transitionId, false)
                for (prevAppState in previousSameWindowAbstractStates) {
                    if (prevAppState.isSimlarAbstractState(currentAbstractState, 0.8)) {
                       /* if (!AbstractStateManager.INSTANCE.goBackAbstractActions.contains(this.abstractAction)) {
                            val inputs =
                                this.source.getInputsByAbstractAction(this.abstractAction)
                            inputs.forEach {
                                if (!Input.goBackInputs.contains(it))
                                    Input.goBackInputs.add(it)
                            }
                        } else
                            AbstractStateManager.INSTANCE.goBackAbstractActions.add(this.abstractAction)*/
                        this.guardEnabled = true
                        this.dependentAbstractStates.add(prevAppState)
                        break
                    }
                }
            }
        }
    }
    companion object{
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