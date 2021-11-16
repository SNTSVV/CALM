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
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.modelFeatures.graph.*
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.droidmate.explorationModel.ExplorationTrace
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import kotlin.collections.ArrayList

class DSTG(private val graph: IGraph<AbstractState, AbstractTransition> =
                              Graph(AbstractStateManager.INSTANCE.appResetState,
                                      stateComparison = { a, b -> a == b },
                                      labelComparison = { a, b ->
                                        a==b
                                      })): IGraph<AbstractState, AbstractTransition> by graph {

    val abstractActionEnables = HashMap<AbstractAction, HashMap<AbstractAction,Pair<Int,Int>>>()
    fun updateAbstractActionEnability(
        abstractTransition: AbstractTransition,
        atua: ATUAMF
    ) {
        if (AbstractStateManager.INSTANCE.goBackAbstractActions.contains(abstractTransition.abstractAction))
            return
        val abstractAction = abstractTransition.abstractAction
        val prevAbstractState = abstractTransition.source
        val newAbstractState = abstractTransition.dest
        val prevWindow = prevAbstractState.window
        abstractActionEnables.putIfAbsent(abstractAction, HashMap())
        val enableAbstractActions =
            abstractActionEnables[abstractAction]!!
        val availableActions = newAbstractState.getAvailableActions()
        val prevAvailableActions = prevAbstractState.getAvailableActions()
        val availableAbstractActions = availableActions.subtract(prevAvailableActions)
        availableAbstractActions.forEach {
            enableAbstractActions.putIfAbsent(it, Pair(0, 0))
            val total = enableAbstractActions[it]!!.first
            val enabled = enableAbstractActions[it]!!.second
            enableAbstractActions.put(it, Pair(total + 1, enabled + 1))
        }
        enableAbstractActions.keys.subtract(availableActions).forEach {
            val total = enableAbstractActions[it]!!.first
            val enabled = enableAbstractActions[it]!!.second
            enableAbstractActions.put(it, Pair(total + 1, enabled))
        }
    }
    override fun add(source: AbstractState, destination: AbstractState?, label: AbstractTransition, updateIfExists: Boolean, weight: Double): Edge<AbstractState, AbstractTransition> {
        val edge = graph.add(source, destination, label, updateIfExists, weight)
        return edge
    }

    override fun update(source: AbstractState, prevDestination: AbstractState?, newDestination: AbstractState, prevLabel: AbstractTransition, newLabel: AbstractTransition): Edge<AbstractState, AbstractTransition>? {
        val edge = graph.update(source, prevDestination,newDestination, prevLabel, newLabel)
        return edge
    }

    override fun remove(edge: Edge<AbstractState, AbstractTransition>): Boolean {
        edge.source.data.abstractTransitions.remove(edge.label)
        return graph.remove(edge)
    }
    fun dump(statementCoverageMF: StatementCoverageMF, explorationContext: ExplorationContext<*, *, *>, bufferedWriter: BufferedWriter) {
        bufferedWriter.write(header())
        //val fromResetState = AbstractStateManager.instance.launchAbstractStates.get(AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH)!!
        //val fromResetAbstractState = AbstractStateManager.instance.getAbstractState(fromResetState)!!
        val dumpedSourceStates = ArrayList<AbstractState>()
        this.getVertices().filter{it.data.guiStates.isNotEmpty()
                || AbstractStateManager.INSTANCE.usefulUnseenBaseAbstractStates.contains(it.data)
        }.forEach {
            recursiveDump(it.data,statementCoverageMF,dumpedSourceStates,explorationContext, bufferedWriter)
        }
    }

    private fun header(): String {
        return "[1]SourceState;[2]ResultingState;[3]ActionType;[4]InteractedAVM;[5]ActionExtra;[6]InteractionData;[7]GuardEnabled;[8]DependentAbstractStates;[9]EventHandlers;[10]CoveredUpdatedMethods;[11]CoveredUpdatedStatements;[12]CoveredMethods;[13]GUITransitionIDs;[14]modelVersion"
    }

    fun recursiveDump(sourceAbstractState: AbstractState, statementCoverageMF: StatementCoverageMF, dumpedSourceStates: ArrayList<AbstractState> , explorationContext: ExplorationContext<*,*,*> ,bufferedWriter: BufferedWriter) {
        dumpedSourceStates.add(sourceAbstractState)
        val explicitEdges = this.edges(sourceAbstractState).filter { it.label.isExplicit()
                && it.destination!=null
                && sourceAbstractState.abstractTransitions.contains(it.label)
                && (it.label.interactions.isNotEmpty() ||
                (it.label.modelVersion == ModelVersion.BASE
                        && sourceAbstractState.guiStates.isEmpty()
                        && sourceAbstractState.modelVersion == ModelVersion.BASE)) }
        val nextSources = ArrayList<AbstractState>()
        explicitEdges.map { it.label }.distinct().forEach { edge ->
            if (!nextSources.contains(edge.dest) && !dumpedSourceStates.contains(edge.dest)) {
                nextSources.add(edge.dest)
            }
            val abstractTransitionInfo = "${sourceAbstractState.abstractStateId};${edge.dest.abstractStateId};" +
                    "${edge.abstractAction.actionType};${edge.abstractAction.attributeValuationMap?.avmId};${edge.abstractAction.extra};${edge.data};${edge.guardEnabled};" +
                    "\"${edge.dependentAbstractStates.map { it.abstractStateId }.joinToString(";")}\";" +
                    "\"${getInteractionHandlers(edge,statementCoverageMF)}\";" +
                    "\"${getCoveredModifiedMethods(edge,statementCoverageMF)}\";" +
                    "\"${getCoveredUpdatedStatements(edge,statementCoverageMF)}\";" +
                    "\"${getCoveredMethods(edge, statementCoverageMF)}\";" +
                    "\"${edge.interactions.map {String.format("%s_%s",explorationContext.explorationTrace.id,it.actionId)}.joinToString(separator = ";")}\";${edge.modelVersion}"
            bufferedWriter.newLine()
            bufferedWriter.write(abstractTransitionInfo)
        }
        nextSources.forEach {
            recursiveDump(it,statementCoverageMF,dumpedSourceStates, explorationContext, bufferedWriter)
        }
    }

    private fun getCoveredModifiedMethods(edge: AbstractTransition, statementCoverageMF: StatementCoverageMF): String {
        return edge.modifiedMethods.filterValues { it == true }.map { statementCoverageMF.getMethodName(it.key) }.joinToString(separator = ";")
    }

    private fun getCoveredUpdatedStatements(edge: AbstractTransition, statementCoverageMF: StatementCoverageMF): String {
        return edge.modifiedMethodStatement.filterValues { it == true }.map { it.key }.joinToString(separator = ";")
    }

    private fun getCoveredMethods (edge: AbstractTransition, statementCoverageMF: StatementCoverageMF): String {
        return edge.methodCoverage.map { statementCoverageMF.getMethodName(it) }.joinToString(separator = ";")
    }

    private fun getInteractionHandlers(edge: AbstractTransition, statementCoverageMF: StatementCoverageMF) =
            edge.handlers.filter { it.value == true }.map { it.key }.map { statementCoverageMF.getMethodName(it) }.joinToString(separator = ";")

    fun cleanPredictedAbstractStates() {
        edges().filter { it.destination?.data is UncertainAbstractState }.forEach {
            this.remove(it)
        }
    }

    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(DSTG::class.java) }
    }
}