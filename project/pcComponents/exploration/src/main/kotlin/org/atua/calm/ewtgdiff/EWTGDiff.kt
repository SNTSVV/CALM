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

package org.atua.calm.ewtgdiff

import org.atua.calm.TargetInputReport
import org.atua.calm.modelReuse.ModelHistoryInformation
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AttributePath
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.dstg.reducer.AbstractionFunction2
import org.atua.modelFeatures.dstg.reducer.DecisionNode2
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.WindowTransition
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class EWTGDiff private constructor(){
    val windowDifferentSets: HashMap<String, DifferentSet<Window>> = HashMap()
    val widgetDifferentSets: HashMap<String, DifferentSet<EWTGWidget>> = HashMap()
    val transitionDifferentSets: HashMap<String, DifferentSet<Edge<Window, WindowTransition>>> = HashMap()
    val removedAbstractStates = ArrayList<AbstractState>()
    val replacingInputs = ArrayList<Input>()

    fun getAddedWidgets(): List<EWTGWidget> {
        if (widgetDifferentSets.containsKey("AdditionSet")) {
            return (widgetDifferentSets["AdditionSet"]!! as AdditionSet<EWTGWidget>).addedElements
        }
        return emptyList<EWTGWidget>()
    }

    fun getReplacingWidget(): List<EWTGWidget> {
        val replacementWidgets = ArrayList<EWTGWidget>()
        if (widgetDifferentSets.containsKey("ReplacementSet")) {
            replacementWidgets.addAll ((widgetDifferentSets["ReplacementSet"]!! as ReplacementSet<EWTGWidget>).replacedElements.map { it.new })
        }
        /*if (widgetDifferentSets.containsKey("RetainerSet")) {
            replacementWidgets.addAll ((widgetDifferentSets["RetainerSet"]!! as RetainingSet<EWTGWidget>).replacedElements.map { it.new })
        }*/
        return replacementWidgets
    }


    fun loadFromFile(filePath: Path, atuamf: org.atua.modelFeatures.ATUAMF) {
        if (!Files.exists(filePath))
            return
        val jsonData = String(Files.readAllBytes(filePath))
        val ewtgdiffJson = JSONObject(jsonData)
        ewtgdiffJson.keys().forEach { key->
            if (key == "windowDifferences") {
                loadWindowDifferences(ewtgdiffJson.get(key) as JSONObject)
            }
            if (key == "widgetDifferences") {
                loadWidgetDifferences(ewtgdiffJson.get(key) as JSONObject)
            }
            if (key == "transitionDifferences") {
                loadTransitionDifferences(ewtgdiffJson.get(key) as JSONObject,atuamf)
            }
        }
        if (windowDifferentSets.containsKey("DeletionSet")) {
            for (deleted in (windowDifferentSets.get("DeletionSet")!! as DeletionSet<Window>).deletedElements) {
                removeWindow(deleted, atuamf)
                WindowManager.instance.baseModelWindows.filter {
                    it is Dialog && it.ownerActivitys.contains(deleted) }.forEach {
                        removeWindow(it,atuamf)
                        AbstractAction.abstractActionsByWindow.remove(it)
                }

            }
        }
        if (windowDifferentSets.containsKey("ReplacementSet")) {
            for (replacement in (windowDifferentSets.get("ReplacementSet")!! as ReplacementSet<Window>).replacedElements) {
                replaceWindow(replacement, atuamf)
                WindowManager.instance.baseModelWindows.remove(replacement.old)
            }
            val replacements = (windowDifferentSets.get("ReplacementSet")!! as ReplacementSet<Window>).replacedElements.map { Pair(it.old,it.new) }.toMap()


        }
        if (windowDifferentSets.containsKey("RetainerSet")) {
            for (replacement in (windowDifferentSets.get("RetainerSet")!! as RetainingSet<Window>).replacedElements) {
                replaceWindow(replacement, atuamf)
                WindowManager.instance.baseModelWindows.remove(replacement.old)
            }

            val replacements = (windowDifferentSets.get("RetainerSet")!! as RetainingSet<Window>).replacedElements.map { Pair(it.old,it.new) }.toMap()
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.forEach {
                it.abstractTransitions.forEach {
                    /*if (replacements.contains(it.prevWindow)) {
                        it.prevWindow = replacements.get(it.prevWindow)
                    }*/
                    // TODO check
                }
            }
        }
        WindowManager.instance.baseModelWindows.filter {
                    it is Dialog || it is OutOfApp || it is Activity
        }.forEach {w->
            val newWindow = w.copyToRunningModel()
            replaceWindow(Replacement(w,newWindow),atuamf)
            WindowManager.instance.updatedModelWindows.add(newWindow)
            WindowManager.instance.baseModelWindows.remove(w)
            /*val exisitingWindow = WindowManager.instance.updatedModelWindows.find { it.javaClass == w.javaClass && it.classType == w.classType }
            if (exisitingWindow != null) {
                // replace old window with the exisiting one
                replaceWindow(Replacement(w,exisitingWindow),atuamf)
            } else {

            }*/
        }

        if (widgetDifferentSets.containsKey("DeletionSet")) {
            for (deleted in (widgetDifferentSets.get("DeletionSet")!! as DeletionSet<EWTGWidget>).deletedElements) {
                updateEWTGWidgetAVMMappingWithDeleted(deleted,atuamf)
                deleted.window.widgets.remove(deleted)
                updateWindowHierarchyWithDeleted(deleted)
                ModelHistoryInformation.INSTANCE.inputUsefulness.filter {
                    it.key.widget == deleted
                }.forEach {
                    ModelHistoryInformation.INSTANCE.inputUsefulness.remove(it.key)
                }
            }
        }

        if (widgetDifferentSets.containsKey("ReplacementSet")) {
            for (replacement in (widgetDifferentSets.get("ReplacementSet")!! as ReplacementSet<EWTGWidget>).replacedElements) {
                // update avm-ewtgwidget mapping
                updateReplacementInput(replacement,true,atuamf)
                updateAbstractionFunction(replacement)
                // update EWTGWidget structure
                updateWindowHierarchy(replacement)
                updateEWTGWidgetAVMMapping(replacement)

            }
        }

        if (widgetDifferentSets.containsKey("RetainerSet")) {
            for (replacement in (widgetDifferentSets.get("RetainerSet")!! as RetainingSet<EWTGWidget>).replacedElements) {
                updateReplacementInput(replacement,false,atuamf)
                // update EWTGWidget structure
                updateWindowHierarchy(replacement)
                updateAbstractionFunction(replacement)
                updateEWTGWidgetAVMMapping(replacement)
            }
        }
        if (transitionDifferentSets.containsKey("RetainerSet")) {
            for (replacement in (transitionDifferentSets.get("RetainerSet")!! as RetainingSet<Edge<Window, WindowTransition>>).replacedElements) {
                replacement.new.label.input.eventHandlers.addAll(replacement.old.label.input.eventHandlers)
            }
        }

        AbstractStateManager.INSTANCE.ABSTRACT_STATES.forEach {
            val toRemoveMappings = ArrayList<AttributeValuationMap>()
            it.EWTGWidgetMapping.forEach {
                if (WindowManager.instance.baseModelWindows.contains(it.value.window)) {
                    toRemoveMappings.add(it.key)
                }
            }
            toRemoveMappings.forEach { avm->
                it.EWTGWidgetMapping.remove(avm)
            }
        }
    }

    private fun removeWindow(deleted: Window, atuamf: ATUAMF) {
        val toRemoves = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == deleted }
        removedAbstractStates.addAll(toRemoves)
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.removeAll(toRemoves)
        AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.remove(deleted)
        AttributeValuationMap.allWidgetAVMHashMap.remove(deleted)
        AttributeValuationMap.attributePath_AttributeValuationMap.remove(deleted)
        AttributePath.allAttributePaths.remove(deleted)
        // WindowManager.instance.baseModelWindows.remove(deleted)
        toRemoves.forEach {
            atuamf.dstg.removeVertex(it)
        }
        ModelHistoryInformation.INSTANCE.inputUsefulness.filter {
            it.key.sourceWindow == deleted
        }.forEach {
            ModelHistoryInformation.INSTANCE.inputUsefulness.remove(it.key)
        }
    }

    private fun updateEWTGWidgetAVMMappingWithDeleted(deleted: EWTGWidget, atuamf: org.atua.modelFeatures.ATUAMF) {
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == deleted.window }.forEach { abstractState ->
            val toDeleteAvms = abstractState.EWTGWidgetMapping.filter { ewtgWidget -> ewtgWidget.value == deleted }.keys
            val toRemoveAbstractTransitions = ArrayList<AbstractTransition>()
            toDeleteAvms.forEach { avm ->
                abstractState.EWTGWidgetMapping.remove(avm)
                toRemoveAbstractTransitions.addAll(abstractState.abstractTransitions.filter { it.abstractAction.isWidgetAction()
                        && it.abstractAction.attributeValuationMap == avm})
                abstractState.removeAVMAndRecomputeHashCode(avm)
            }

//            abstractState.attributeValuationMaps.removeIf { toDeleteAvms.contains(it) }

            toRemoveAbstractTransitions.forEach {
                abstractState.abstractTransitions.remove(it)
                val edge = atuamf.dstg.edge(it.source,it.dest,it)
                if (edge!=null)
                    atuamf.dstg.remove(edge)
            }
        }
    }

    private fun updateWindowHierarchyWithDeleted(deleted: EWTGWidget) {
        val parent = deleted.parent
        if (parent != null)
            parent.children.remove(deleted)
        deleted.children.forEach {
            it.parent = parent
        }
    }

    private fun updateEWTGWidgetAVMMapping(replacement: Replacement<EWTGWidget>) {
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.forEach { appState ->
            val toBeReplacedAvms = appState.EWTGWidgetMapping.filter { it.value == replacement.old }.keys
            toBeReplacedAvms.forEach { avm ->
                appState.EWTGWidgetMapping.put(avm, replacement.new)
            }
            appState.getAvailableActions()
                .filter { toBeReplacedAvms.contains(it.attributeValuationMap)}
                .forEach {  abstractAction ->
                    val abstractTransitions = appState.abstractTransitions.filter { it.abstractAction == abstractAction }
                    appState.removeInputAssociatedAbstractAction(abstractAction)
                    Input.getOrCreateInputFromAbstractAction(appState,abstractAction,ModelVersion.RUNNING)
                }
        }
    }

    private fun updateWindowHierarchy(replacement: Replacement<EWTGWidget>) {
        replacement.new.structure = replacement.old.structure
        replacement.old.children.forEach {
            it.parent = replacement.new
        }

        val parent = replacement.old.parent
        if (parent != null) {
            parent.children.remove(replacement.old)
            replacement.new.parent = parent
        }
        replacement.old.window.widgets.remove(replacement.old)
    }

    fun updateReplacementInput(replacement: Replacement<EWTGWidget>, isReplaced: Boolean, atuamf: ATUAMF) {
        replacement.old.window.inputs.filter { it.widget == replacement.old }.forEach { oldInput ->
            var existingInputInUpdateVers = replacement.new.window.inputs
                    .find { it.widget == replacement.new && it.eventType == oldInput.eventType }
            if (existingInputInUpdateVers == null) {
                existingInputInUpdateVers = Input.getOrCreateInput(
                    eventHandlers = emptySet(),
                    eventTypeString = oldInput.eventType.toString(),
                    widget = replacement.new,
                    createdAtRuntime = true,
                    sourceWindow = replacement.new.window,
                    modelVersion = ModelVersion.RUNNING
                )
            }
            if (existingInputInUpdateVers != null) {
                replacement.new.window.inputs.remove(oldInput)
                /*if (*//*!isReplaced &&*//* existingInputInUpdateVers.eventHandlers.intersect(oldInput.eventHandlers).isEmpty()) {
                    existingInputInUpdateVers.eventHandlers.clear()
                    existingInputInUpdateVers.modifiedMethods.clear()
                    existingInputInUpdateVers.modifiedMethodStatement.clear()
                }*/
                val uncoveredHanndlers = existingInputInUpdateVers.eventHandlers.subtract(oldInput.eventHandlers.union(existingInputInUpdateVers.verifiedEventHandlers))
                if (uncoveredHanndlers.isNotEmpty()) {
                    val remainHandlers = uncoveredHanndlers.filter { atuamf.statementMF!!.isModifiedMethod(it) }
                    existingInputInUpdateVers.eventHandlers.clear()
                    existingInputInUpdateVers.eventHandlers.addAll(remainHandlers)
                }
                existingInputInUpdateVers.eventHandlers.addAll(oldInput.eventHandlers.union(existingInputInUpdateVers.verifiedEventHandlers))
                val beforeCnt = existingInputInUpdateVers.modifiedMethods.size
                val reachableModifiedMethods = existingInputInUpdateVers.eventHandlers.map { handler->
                    atuamf.modifiedMethodWithTopCallers.filter { it.value.contains(handler) }.keys
                }.flatten().distinct()
                val unreachableMethods = existingInputInUpdateVers.modifiedMethods.keys.subtract(reachableModifiedMethods)
                unreachableMethods.forEach {
                    existingInputInUpdateVers.modifiedMethods.remove(it)
                }
                existingInputInUpdateVers.modifiedMethods.putAll(oldInput.modifiedMethods)
                val afterCnt = existingInputInUpdateVers.modifiedMethods.size
                existingInputInUpdateVers.coveredMethods.putAll(oldInput.coveredMethods)
                existingInputInUpdateVers.exercisedInThePast = oldInput.exercisedInThePast
                if (oldInput.modifiedMethods.isNotEmpty()) {
                    TargetInputReport.INSTANCE.targetIdentifiedByBaseModel.add(existingInputInUpdateVers)
                }
            }
        }

        ModelHistoryInformation.INSTANCE.inputUsefulness.filter {
            it.key.widget == replacement.old
        }.forEach {
            val newInput = replacement.new.window.inputs.find { input->
                input.eventType == it.key.eventType
                        && input.widget == replacement.new
            }
            if (newInput != null) {
                ModelHistoryInformation.INSTANCE.inputUsefulness.put(newInput,it.value)
            }
            ModelHistoryInformation.INSTANCE.inputUsefulness.remove(it.key)
        }
    }

    private fun updateAbstractionFunction(replacement: Replacement<EWTGWidget>) {
        var currentDecisionNode: DecisionNode2? = AbstractionFunction2.INSTANCE.root
        while (currentDecisionNode != null) {
            if (currentDecisionNode.ewtgWidgets.remove(replacement.old)) {
                currentDecisionNode.ewtgWidgets.add(replacement.new)
            }
            currentDecisionNode = currentDecisionNode!!.nextNode
        }
    }

    private fun resolveWindowNameConflict(window: Window): Boolean {
        if (WindowManager.instance.updatedModelWindows.any {
                    it != window
                            && it.windowId == window.windowId
                }) {
            if (window is Dialog)
                window.windowId = Dialog.getNodeId()
            else if (window is OutOfApp)
                window.windowId = OutOfApp.getNodeId()
            else if (window is Activity)
                window.windowId = Activity.getNodeId()
            return true
        } else
            return false
    }

    private fun replaceWindow(replacement: Replacement<Window>, atuamf: org.atua.modelFeatures.ATUAMF) {
        val oldWindowAVMs = AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.get(replacement.old)

        if (oldWindowAVMs!=null) {
            AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.put(replacement.new, oldWindowAVMs)
        }

        val oldWindowAttributePaths = AttributePath.allAttributePaths.get(replacement.old)
        if (oldWindowAttributePaths!=null) {
            AttributePath.allAttributePaths.put(replacement.new,oldWindowAttributePaths )
            oldWindowAttributePaths.forEach { t, u ->
                u.window = replacement.new
            }
        }

        val oldWindowAttributePathsByAVM = AttributeValuationMap.attributePath_AttributeValuationMap.get(replacement.old)
        if (oldWindowAttributePathsByAVM!=null) {
            AttributeValuationMap.attributePath_AttributeValuationMap.put(replacement.new,oldWindowAttributePathsByAVM )
        }
        val oldWindowAbstractActions = AbstractAction.abstractActionsByWindow.get(replacement.old)
        if (oldWindowAbstractActions != null) {
            AbstractAction.abstractActionsByWindow.put(replacement.new,oldWindowAbstractActions)
            oldWindowAbstractActions.forEach {
                it.window = replacement.new
            }
        }

        atuamf.modifiedMethodsByWindow.remove(replacement.old)
        replacement.old.widgets.forEach {
            replacement.new.widgets.add(it)
            it.window = replacement.new
        }
        replacement.old.widgets.clear()
        replacement.old.inputs.filter{it.widget!=null}.forEach {
            it.sourceWindow = replacement.new
            replacement.new.inputs.add(it)
        }
        replacement.old.inputs.filter { it.widget == null }.forEach { oldInput->
            updateNonWidgetInput(replacement, oldInput, atuamf)
        }
        replacement.old.inputs.clear()
        val toRemoveEdges = ArrayList<Edge<Window, WindowTransition>>()
        atuamf.wtg.edges(replacement.old).forEach {
            atuamf.wtg.add(replacement.new, it.destination?.data, it.label)
            toRemoveEdges.add(it)
        }
        atuamf.wtg.edges().forEach {
            if (it.destination?.data == replacement.old) {
                atuamf.wtg.add(it.source.data, replacement.new, it.label)
                toRemoveEdges.add(it)
            }
        }
        toRemoveEdges.forEach {
            atuamf.wtg.remove(it)
        }
        WindowManager.instance.baseModelWindows.filter {
            it is Dialog && it.ownerActivitys.contains(replacement.old)
        }.forEach {
            (it as Dialog).ownerActivitys.remove(replacement.old)
            it.ownerActivitys.add(replacement.new)
        }
        ModelHistoryInformation.INSTANCE.inputUsefulness.filter {
            it.key.sourceWindow == replacement.old
                    && it.key.widget == null
        }.forEach {
            val newInput = replacement.new.inputs.find { input->
                input.eventType == it.key.eventType
                        && input.widget == null
            }
            if (newInput != null) {
                ModelHistoryInformation.INSTANCE.inputUsefulness.put(newInput,it.value)
            }
            ModelHistoryInformation.INSTANCE.inputUsefulness.remove(it.key)
        }
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == replacement.old }.forEach { abstractState ->
            abstractState.window = replacement.new
            abstractState.getAvailableActions()
                .forEach { abstractAction ->
                    val oldInputs = ArrayList(abstractState.getInputsByAbstractAction(abstractAction))
                    oldInputs.forEach {
                        if (it.widget!=null) {
                            it.sourceWindow = replacement.new
                        }
                    }
                    val abstractTransitions = abstractState.abstractTransitions.filter { it.abstractAction == abstractAction }
                    val replacedAbstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = abstractAction.actionType,
                        attributeValuationMap = abstractAction.attributeValuationMap,
                        extra = abstractAction.extra,
                        window = replacement.new
                    )
                    val actionCount = abstractState.getActionCount(abstractAction)
                    abstractState.removeAction(abstractAction)
                    abstractState.setActionCount(replacedAbstractAction, actionCount)
                    abstractTransitions.forEach {
                        it.abstractAction = replacedAbstractAction
                    }
                    abstractState.removeInputAssociatedAbstractAction(abstractAction)
                    Input.getOrCreateInputFromAbstractAction(abstractState,replacedAbstractAction, ModelVersion.RUNNING)
                }
            //TODO update abstractAction's window
        }
    }

    private fun updateNonWidgetInput(
        replacement: Replacement<Window>,
        oldInput: Input,
        atuamf: ATUAMF
    ) {
        var existingInputInUpdateVers = replacement.new.inputs
            .find { it.eventType == oldInput.eventType }
        if (existingInputInUpdateVers == null) {
            existingInputInUpdateVers = Input.getOrCreateInput(
                eventHandlers = emptySet(),
                eventTypeString = oldInput.eventType.toString(),
                widget = null,
                createdAtRuntime = true,
                sourceWindow = replacement.new,
                modelVersion = ModelVersion.RUNNING
            )
        }
        if (existingInputInUpdateVers != null) {
            replacement.old.inputs.remove(oldInput)
            /*if (*//*!isReplaced &&*//* existingInputInUpdateVers.eventHandlers.intersect(oldInput.eventHandlers).isEmpty()) {
                        existingInputInUpdateVers.eventHandlers.clear()
                        existingInputInUpdateVers.modifiedMethods.clear()
                        existingInputInUpdateVers.modifiedMethodStatement.clear()
                    }*/
            val uncoveredHanndlers = existingInputInUpdateVers.eventHandlers.subtract(oldInput.eventHandlers)
            if (uncoveredHanndlers.isNotEmpty()) {
                val remainHandlers = uncoveredHanndlers.filter { atuamf.statementMF!!.isModifiedMethod(it) }
                existingInputInUpdateVers.eventHandlers.clear()
                existingInputInUpdateVers.eventHandlers.addAll(remainHandlers)
            }
            existingInputInUpdateVers.eventHandlers.addAll(oldInput.eventHandlers)
            val beforeCnt = existingInputInUpdateVers.modifiedMethods.size
            val reachableModifiedMethods = existingInputInUpdateVers.eventHandlers.map { handler ->
                atuamf.modifiedMethodWithTopCallers.filter { it.value.contains(handler) }.keys
            }.flatten().distinct()
            val unreachableMethods = existingInputInUpdateVers.modifiedMethods.keys.subtract(reachableModifiedMethods)
            unreachableMethods.forEach {
                existingInputInUpdateVers.modifiedMethods.remove(it)
            }
            existingInputInUpdateVers.modifiedMethods.putAll(oldInput.modifiedMethods)
            val afterCnt = existingInputInUpdateVers.modifiedMethods.size
            existingInputInUpdateVers.coveredMethods.putAll(oldInput.coveredMethods)
        }
    }

    private fun loadTransitionDifferences(jsonObject: JSONObject,atuamf: org.atua.modelFeatures.ATUAMF) {
        jsonObject.keys().forEach {key->
            if (key == "transitionAdditions") {
                val transitionAdditionSet = AdditionSet<Edge<Window,WindowTransition>>()
                transitionDifferentSets.put("AdditionSet",transitionAdditionSet)
                loadTransitionAdditionSet(jsonObject.get(key) as JSONArray, transitionAdditionSet,atuamf)
            }
            if (key == "transitionDeletions") {
                val transitionDeletionSet = DeletionSet<Edge<Window,WindowTransition>>()
                transitionDifferentSets.put("DeletionSet",transitionDeletionSet)
                loadTransitionDeletionSet(jsonObject.get(key) as JSONArray, transitionDeletionSet,atuamf)
            }
            if (key == "transitionReplacements") {
                val transitionReplacementSet = ReplacementSet<Edge<Window,WindowTransition>>()
                transitionDifferentSets.put("ReplacementSet",transitionReplacementSet)
                loadTransitionReplacementSet(jsonObject.get(key) as JSONArray, transitionReplacementSet,atuamf)

            }
            if (key == "transitionRetainers") {
                val transitionRetainSet = RetainingSet<Edge<Window,WindowTransition>>()
                transitionDifferentSets.put("RetainerSet",transitionRetainSet)
                loadTransitionRetainSet(jsonObject.get(key) as JSONArray, transitionRetainSet,atuamf)
            }
        }
    }

    private fun loadTransitionRetainSet(jsonArray: JSONArray, transitionRetainSet: RetainingSet<Edge<Window, WindowTransition>>, atuamf: org.atua.modelFeatures.ATUAMF) {
        for (item in jsonArray) {
            val replacementJson = item as JSONObject
            val oldTransitionFullId = replacementJson.get("oldElement").toString()
            val newTransitionFullId = replacementJson.get("newElement").toString()
            val oldTransition = parseTransition(oldTransitionFullId,atuamf,WindowManager.instance.baseModelWindows)
            val newTransition = parseTransition(newTransitionFullId,atuamf,WindowManager.instance.updatedModelWindows)
            if (oldTransition != null && newTransition!=null) {
                transitionRetainSet.replacedElements.add(Replacement(oldTransition,newTransition))
                newTransition.label.input.eventHandlers.addAll(oldTransition.label.input.eventHandlers)
            } else {
                val oldInput = parseInput(oldTransitionFullId,atuamf,WindowManager.instance.baseModelWindows)
                val newInput = parseInput(newTransitionFullId,atuamf,WindowManager.instance.updatedModelWindows)
                if (oldInput!=null && newInput!=null) {
                    newInput.eventHandlers.addAll(oldInput.eventHandlers)
                }
            }
        }
    }

    private fun loadTransitionReplacementSet(jsonArray: JSONArray, transitionReplacementSet: ReplacementSet<Edge<Window, WindowTransition>>, atuamf: org.atua.modelFeatures.ATUAMF) {
        for (item in jsonArray) {
            val replacementJson = item as JSONObject
            val oldTransitionFullId = replacementJson.get("oldElement").toString()
            val newTransitionFullId = replacementJson.get("newElement").toString()
            val oldTransition = parseTransition(oldTransitionFullId,atuamf,WindowManager.instance.baseModelWindows)
            val newTransition = parseTransition(newTransitionFullId,atuamf,WindowManager.instance.updatedModelWindows)
            if (oldTransition != null && newTransition!=null) {
                transitionReplacementSet.replacedElements.add(Replacement(oldTransition,newTransition))
                newTransition.label.input.eventHandlers.addAll(oldTransition.label.input.eventHandlers)
                if (!replacingInputs.contains(newTransition.label.input)) {
                    replacingInputs.add(newTransition.label.input)
                }
            } else {
                val oldInput = parseInput(oldTransitionFullId,atuamf,WindowManager.instance.baseModelWindows)
                val newInput = parseInput(newTransitionFullId,atuamf,WindowManager.instance.updatedModelWindows)
                if (oldInput!=null && newInput!=null) {
                    newInput.eventHandlers.addAll(oldInput.eventHandlers)
                    if (!replacingInputs.contains(newInput)) {
                        replacingInputs.add(newInput)
                    }
                }
            }
        }
    }

    private fun loadTransitionDeletionSet(jsonArray: JSONArray, transitionDeletionSet: DeletionSet<Edge<Window, WindowTransition>>, atuamf: org.atua.modelFeatures.ATUAMF) {
        jsonArray.forEach {item ->
            var transition : Edge<Window,WindowTransition>? = null
            transition = parseTransition(item.toString(), atuamf, WindowManager.instance.baseModelWindows)
            if (transition!=null) {
                transitionDeletionSet.deletedElements.add(transition)
            }
        }
    }

    private fun loadTransitionAdditionSet(jsonArray: JSONArray, transitionAdditionSet: AdditionSet<Edge<Window, WindowTransition>>, atuamf: org.atua.modelFeatures.ATUAMF) {
        jsonArray.forEach {item ->
            var transition : Edge<Window,WindowTransition>? = null
            transition = parseTransition(item.toString(), atuamf, WindowManager.instance.updatedModelWindows)
            if (transition!=null) {
                transitionAdditionSet.addedElements.add(transition)
            }
        }
    }

    private fun parseTransition(item: String, atuamf: org.atua.modelFeatures.ATUAMF, windowList: ArrayList<Window>): Edge<Window, WindowTransition>? {
        var transition: Edge<Window,WindowTransition>? = null
        val transitionFullId = item.toString()
        val split = transitionFullId.split("_")
        val sourceWindowId = split[0]!!.replace("WIN", "")
        val destWindowId = split[1]!!.replace("WIN", "")
        val action = split[2]!!.replace("-","_")
        if (!Input.isIgnoreEvent(action)) {
            var widgetId = ""
            var ignoreWidget = false
            if (Input.isNoWidgetEvent(action) || split.size < 4) {
                ignoreWidget = true
            } else {
                widgetId = split[3]!!.replace("WID", "")
            }
            val sourceWindow = windowList.find { it.windowId == sourceWindowId }
            val destWindow = windowList.find { it.windowId == destWindowId }
            if (sourceWindow == null || destWindow == null)
                return transition
            val widget = if (ignoreWidget)
                null
            else
                sourceWindow.widgets.find { it.widgetId == widgetId }
            val input = sourceWindow.inputs.find {
                it.eventType == EventType.valueOf(action)
                        && it.widget == widget
            }
            transition = atuamf.wtg.edges(sourceWindow).find {
                it.destination?.data == destWindow
                        && it.label.input == input
            }

        }
        return transition
    }

    private fun parseInput(item: String, atuamf: org.atua.modelFeatures.ATUAMF, windowList: ArrayList<Window>): Input? {
        var input: Input? = null
        val transitionFullId = item.toString()
        val split = transitionFullId.split("_")
        val sourceWindowId = split[0]!!.replace("WIN", "")
        val destWindowId = split[1]!!.replace("WIN", "")
        val action = split[2]!!.replace("-","_")
        if (!Input.isIgnoreEvent(action)) {
            var widgetId = ""
            var ignoreWidget = false
            if (Input.isNoWidgetEvent(action) || split.size < 4) {
                ignoreWidget = true
            } else {
                widgetId = split[3]!!.replace("WID", "")
            }
            val sourceWindow = windowList.find { it.windowId == sourceWindowId }
            val destWindow = windowList.find { it.windowId == destWindowId }
            if (sourceWindow == null || destWindow == null)
                return input
            val widget = if (ignoreWidget)
                null
            else
                sourceWindow.widgets.find { it.widgetId == widgetId }
            input = sourceWindow.inputs.find {
                it.eventType == EventType.valueOf(action)
                        && it.widget == widget
            }
        }
        return input
    }
    private fun loadWidgetDifferences(jsonObject: JSONObject) {
        jsonObject.keys().forEach {key->
            if (key == "widgetAdditions") {
                val widgetAdditionSet = AdditionSet<EWTGWidget>()
                widgetDifferentSets.put("AdditionSet",widgetAdditionSet)
                loadWidgetAdditionSet(jsonObject.get(key) as JSONArray, widgetAdditionSet)
            }
            if (key == "widgetDeletions") {
                val widgetDeletionSet = DeletionSet<EWTGWidget>()
                widgetDifferentSets.put("DeletionSet",widgetDeletionSet)
                loadWidgetDeletionSet(jsonObject.get(key) as JSONArray, widgetDeletionSet)
            }
            if (key == "widgetReplacements") {
                val widgetReplacementSet = ReplacementSet<EWTGWidget>()
                widgetDifferentSets.put("ReplacementSet", widgetReplacementSet)
                loadWidgetReplacementSet(jsonObject.get(key) as JSONArray, widgetReplacementSet)
            }
            if (key == "widgetRetainers") {
                val widgetRetainingSet = RetainingSet<EWTGWidget>()
                widgetDifferentSets.put("RetainerSet",widgetRetainingSet)
                loadWidgetRetainerSet(jsonObject.get(key) as JSONArray, widgetRetainingSet)
            }
        }
    }

    private fun loadWidgetRetainerSet(jsonArray: JSONArray, widgetRetainingSet: RetainingSet<EWTGWidget>) {
        for (item in jsonArray) {
            val replacementJson = item as JSONObject
            val oldWidgetFullId = replacementJson.get("oldElement").toString()
            val newWidgetFullId = replacementJson.get("newElement").toString()
            val oldWindowId = oldWidgetFullId.split("_")[0]!!.replace("WIN","")
            val oldWidgetId = oldWidgetFullId.split("_")[1]!!.replace("WID","")
            var oldWindow = WindowManager.instance.baseModelWindows.find { it.windowId == oldWindowId }
            if (oldWindow==null) {
                // this could be an OptionsMenu
                val haveSimilarWidgetWindow =  WindowManager.instance.baseModelWindows.find { it.widgets.any { it.widgetId == oldWidgetId } }
                if (haveSimilarWidgetWindow != null)
                    oldWindow = haveSimilarWidgetWindow

            }
            if (oldWindow==null) {
               log.warn("Cannot get the old window with id $oldWindowId")
                continue
            }
            val oldWidget =  oldWindow.widgets.find { it.widgetId == oldWidgetId }
            if (oldWidget == null) {
                log.warn("Cannot get the old widget with id $oldWidgetId")
                continue
            }
            val newWindowId = newWidgetFullId.split("_")[0]!!.replace("WIN","")
            val newWidgetId = newWidgetFullId.split("_")[1]!!.replace("WID","")
            var newWindow = WindowManager.instance.updatedModelWindows.find { it.windowId == newWindowId }
            if (newWindow == null) {
                // this could be an OptionsMenu
                val haveSimilarWidgetWindow =  WindowManager.instance.updatedModelWindows.find { it.widgets.any { it.widgetId == newWidgetId } }
                if (haveSimilarWidgetWindow != null)
                    newWindow = haveSimilarWidgetWindow
            }
            if (newWindow==null) {
                log.warn("Cannot get the window with id $newWidgetId")
                continue
            }
            val newWidget =  newWindow.widgets.find { it.widgetId == newWidgetId }
            if (newWidget == null) {
                continue
            }
            widgetRetainingSet.replacedElements.add(Replacement<EWTGWidget>(oldWidget,newWidget))
        }
    }

    private fun loadWidgetReplacementSet(jsonArray: JSONArray, widgetReplacementSet: ReplacementSet<EWTGWidget>) {
        for (item in jsonArray) {
            val replacementJson = item as JSONObject
            val oldWidgetFullId = replacementJson.get("oldElement").toString()
            val newWidgetFullId = replacementJson.get("newElement").toString()
            val oldWindowId = oldWidgetFullId.split("_")[0]!!.replace("WIN","")
            val oldWidgetId = oldWidgetFullId.split("_")[1]!!.replace("WID","")
            var oldWindow = WindowManager.instance.baseModelWindows.find { it.windowId == oldWindowId }
            if (oldWindow==null) {
                // this could be an OptionsMenu
                    val haveSimilarWidgetWindow =  WindowManager.instance.baseModelWindows.find { it.widgets.any { it.widgetId == oldWidgetId } }
                    if (haveSimilarWidgetWindow != null)
                        oldWindow = haveSimilarWidgetWindow

            }
            if (oldWindow == null) {
                log.warn("Cannot get the old window with id $oldWindowId")
                continue
            }
            val oldWidget =  oldWindow.widgets.find { it.widgetId == oldWidgetId }
            if (oldWidget == null) {
               log.warn("Cannot not get the old widget with id $oldWidgetId")
                continue
            }
            val newWindowId = newWidgetFullId.split("_")[0]!!.replace("WIN","")
            val newWidgetId = newWidgetFullId.split("_")[1]!!.replace("WID","")
            var newWindow = WindowManager.instance.updatedModelWindows.find { it.windowId == newWindowId }
            if (newWindow == null) {
                // this could be an OptionsMenu
                val haveSimilarWidgetWindow =  WindowManager.instance.updatedModelWindows.find { it.widgets.any { it.widgetId == newWidgetId } }
                if (haveSimilarWidgetWindow != null)
                    newWindow = haveSimilarWidgetWindow
            }
            if (newWindow==null) {
                log.warn("Cannot get the old window with id $newWidgetId")
            } else {

                val newWidget =  newWindow.widgets.find { it.widgetId == newWidgetId }
                if (newWidget == null) {
                    continue
                }
                widgetReplacementSet.replacedElements.add(Replacement<EWTGWidget>(oldWidget,newWidget))
            }
        }

    }

    private fun loadWidgetDeletionSet(jsonArray: JSONArray, widgetDeletionSet: DeletionSet<EWTGWidget>) {
        jsonArray.forEach {item ->
            val widgetFullId = item.toString()
            val windowId = widgetFullId.split("_")[0]!!.replace("WIN","")
            val widgetId = widgetFullId.split("_")[1]!!.replace("WID","")
            var window = WindowManager.instance.baseModelWindows.find { it.windowId == windowId }
            if (window == null) {
                val haveSimilarWidgetWindow =  WindowManager.instance.baseModelWindows
                    .find { it.widgets.any { it.widgetId == widgetId } }
                if (haveSimilarWidgetWindow != null)
                    window = haveSimilarWidgetWindow
            }
            if (window==null) {
                log.warn("Cannot get the old window with id $windowId")
            } else{

                val widget =  window.widgets.find { it.widgetId == widgetId }
                if (widget != null) {
                    widgetDeletionSet.deletedElements.add(widget)
                }
            }

        }
    }

    private fun loadWidgetAdditionSet(jsonArray: JSONArray, widgetAdditionSet: AdditionSet<EWTGWidget>) {
        jsonArray.forEach {item ->
            val widgetFullId = item.toString()
            val windowId = widgetFullId.split("_")[0]!!.replace("WIN","")
            val widgetId = widgetFullId.split("_")[1]!!.replace("WID","")
            var window = WindowManager.instance.updatedModelWindows.find { it.windowId == windowId }
            if (window == null) {
                // this could be an OptionsMenu
                val haveSimilarWidgetWindow =  WindowManager.instance.updatedModelWindows.find { it.widgets.any { it.widgetId == widgetId } }
                if (haveSimilarWidgetWindow != null)
                    window = haveSimilarWidgetWindow
            }
            if (window==null) {
                log.warn("Cannot get the old window with id $windowId")
            } else {
                val widget = window.widgets.find { it.widgetId == widgetId }
                if (widget != null) {
                    widgetAdditionSet.addedElements.add(widget)
                }
            }

        }
    }

    private fun loadWindowDifferences(jsonObject: JSONObject) {
        jsonObject.keys().forEach {key->
            if (key == "windowAdditions") {
                val windowAdditionSet = AdditionSet<Window>()
                windowDifferentSets.put("AdditionSet",windowAdditionSet)
                loadWindowAdditionSet(jsonObject.get(key) as JSONArray, windowAdditionSet)
            }
            if (key == "windowDeletions") {
                val windowDeletionSet = DeletionSet<Window>()
                windowDifferentSets.put("DeletionSet",windowDeletionSet)
                loadWindowDeletionSet(jsonObject.get(key) as JSONArray, windowDeletionSet)
            }
            if (key == "windowReplacements") {
                val windowReplacementSet = ReplacementSet<Window>()
                windowDifferentSets.put("ReplacementSet",windowReplacementSet)
                loadWindowReplacementSet(jsonObject.get(key) as JSONArray, windowReplacementSet)
            }
            if (key == "windowRetainers") {
                val windowRetainerSet = RetainingSet<Window>()
                windowDifferentSets.put("RetainerSet",windowRetainerSet)
                loadWindowRetainerSet(jsonObject.get(key) as JSONArray, windowRetainerSet)
            }
        }
    }

    private fun loadWindowRetainerSet(jsonArray: JSONArray, windowRetainerSet: RetainingSet<Window>) {
        jsonArray.forEach {item ->
            val replacementJson = item as JSONObject
            val windowOldId = replacementJson.get("oldElement").toString().replace("WIN","")
            val windowNewId = replacementJson.get("newElement").toString().replace("WIN","")
            val oldWindow = WindowManager.instance.baseModelWindows.find { it.windowId == windowOldId }
            val newWindow = WindowManager.instance.updatedModelWindows.find { it.windowId == windowNewId }
            if (oldWindow != null) {
                if (newWindow==null) {
                    log.warn ("Cannot get the new window with id $windowNewId")
                } else
                    windowRetainerSet.replacedElements.add(Replacement<Window>(oldWindow,newWindow))
            }
            else {
                log.warn("Cannot get the old window with id $windowOldId")
            }


        }
    }

    private fun loadWindowReplacementSet(jsonArray: JSONArray, windowReplacementSet: ReplacementSet<Window>) {
        jsonArray.forEach {item ->
            val replacementJson = item as JSONObject
            val windowOldId = replacementJson.get("oldElement").toString().replace("WIN","")
            val windowNewId = replacementJson.get("newElement").toString().replace("WIN","")
            val oldWindow = WindowManager.instance.baseModelWindows.find { it.windowId == windowOldId }
            val newWindow = WindowManager.instance.updatedModelWindows.find { it.windowId == windowNewId }
            if (oldWindow != null && newWindow != null) {
                windowReplacementSet.replacedElements.add(Replacement<Window>(oldWindow,newWindow))
            } else {
                if (oldWindow == null) {
                    log.warn("Cannot get the old window with id $windowOldId")
                }
                if (newWindow == null) {
                    log.warn("Cannot get the new window with id $windowNewId")
                }
            }

        }
    }

    private fun loadWindowDeletionSet(jsonArray: JSONArray, windowDeletionSet: DeletionSet<Window>) {
        jsonArray.forEach {item ->
            val windowId = item.toString().replace("WIN","")
            val window = WindowManager.instance.baseModelWindows.find { it.windowId == windowId }
            if (window==null) {
                log.warn("Cannot get the old window with id $windowId")
            } else
                windowDeletionSet.deletedElements.add(window)
        }
    }

    private fun loadWindowAdditionSet(jsonArray: JSONArray, windowAdditionSet: AdditionSet<Window>) {
        jsonArray.forEach {item ->
            val windowId = item.toString().replace("WIN","")
            val window = WindowManager.instance.updatedModelWindows.find { it.windowId == windowId }
            if (window==null) {
                log.warn("Cannot get the new window with id $windowId")
            } else
                windowAdditionSet.addedElements.add(window)
        }
    }

    companion object {
        @JvmStatic
        val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }
        val instance: EWTGDiff by lazy {
            EWTGDiff()
        }

    }
}