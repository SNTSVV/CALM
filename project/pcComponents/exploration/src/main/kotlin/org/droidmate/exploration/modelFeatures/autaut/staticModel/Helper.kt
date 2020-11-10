package org.droidmate.exploration.modelFeatures.autaut.staticModel

import org.droidmate.deviceInterface.exploration.Rectangle
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.exploration.modelFeatures.autaut.AutAutMF
import org.droidmate.exploration.modelFeatures.autaut.abstractStateElement.AttributeValuationSet
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import kotlin.math.abs

class Helper {
    companion object {

        fun mergeOptionsMenuWithActivity(newState: State<*>, widget_AttributeValuationSetHashMap: HashMap<Widget, AttributeValuationSet>, optionsMenuNode: WTGNode, activityNode: WTGNode, wtg: WindowTransitionGraph, autAutMF: AutAutMF): Boolean {

            var shouldMerge = false
            var containsOptionMenuWidgets = false
            var containsActivityWidgets = false
            var optionsMenuWidgets = ArrayList<StaticWidget>()
            var activityWidgets = ArrayList<StaticWidget>()
            newState.widgets.filter { widget_AttributeValuationSetHashMap.containsKey(it) } .iterator().also {
                while (it.hasNext()) {
                    val widget = it.next()
                    if (autAutMF.getAbstractState(newState)==null)
                        continue
                    val attributeValuationSet = widget_AttributeValuationSetHashMap[widget]!!

                    optionsMenuWidgets.addAll(getStaticWidgets(widget, newState, attributeValuationSet, optionsMenuNode, false, autAutMF))
                    if (optionsMenuWidgets.isEmpty()) {
                        containsOptionMenuWidgets = false
                    } else {
                        containsOptionMenuWidgets = true
                    }
                    if (containsOptionMenuWidgets)
                        break
                }
            }
            newState.widgets.filter { widget_AttributeValuationSetHashMap.containsKey(it) }.iterator().also {
                while (it.hasNext()) {
                    val widget = it.next()
                    if (autAutMF.getAbstractState(newState)==null)
                        continue
                     val attributeValuationSet = widget_AttributeValuationSetHashMap[widget]!!

                    activityWidgets.addAll(getStaticWidgets(widget, newState, attributeValuationSet, activityNode, false, autAutMF))
                    if (activityWidgets.isEmpty()) {
                        containsActivityWidgets = false
                    } else {
                        containsActivityWidgets = true
                    }
                    if (containsActivityWidgets)
                        break
                }
            }
            if (containsActivityWidgets && containsOptionMenuWidgets) {
                shouldMerge = true
            }
            if (shouldMerge) {
                AutAutMF.log.info("Merge $optionsMenuNode to $activityNode")
                wtg.mergeNode(optionsMenuNode, activityNode)
                /*transitionGraph.removeVertex(optionsMenuNode)
                regressionTestingMF.staticEventWindowCorrelation.filter { it.value.containsKey(optionsMenuNode) }.forEach { event, correlation ->
                    correlation.remove(optionsMenuNode)
                }*/


                return true
            }
            return false
        }

        fun calculateMatchScoreForEachNode(guiState: State<*>, allPossibleNodes: List<WTGNode>, appName: String,
                                           autAutMF: AutAutMF): HashMap<WTGNode, Double> {
            val matchWidgets = HashMap<WTGNode, HashMap<Widget,HashSet<StaticWidget>>>()
            val missWidgets = HashMap<WTGNode, HashSet<Widget>>()
            val propertyChangedWidgets = HashMap<WTGNode, HashSet<Widget>>()
            val visibleWidgets = ArrayList<Widget>()
            visibleWidgets.addAll(getVisibleWidgetsForAbstraction(guiState,autAutMF.packageName))
            if (visibleWidgets.isEmpty()) {
                visibleWidgets.addAll(guiState.widgets.filterNot { it.isKeyboard })
            }
            allPossibleNodes.forEach {
                matchWidgets[it] = HashMap()
                missWidgets[it] = HashSet()
                propertyChangedWidgets[it] = HashSet()
            }
            visibleWidgets.iterator().also {
                while (it.hasNext()) {
                    val widget = it.next()
                    if (autAutMF.getAbstractState(guiState)==null)
                        continue
                    val attributeValuationSet = autAutMF.getAbstractState(guiState)!!.getAttributeValuationSet(widget,guiState)!!
                    allPossibleNodes.forEach {
                        val matchingWidget = getStaticWidgets(widget, guiState, attributeValuationSet, it, false, autAutMF)
                        if (matchingWidget.isNotEmpty()) {
                            if (matchWidgets.containsKey(it)) {
                                matchWidgets[it]!!.put(widget, HashSet(matchingWidget))
                            }
                        }
                        else
                        {
                            if (missWidgets.contains(it) && widget.resourceId.isNotBlank()) {
                                missWidgets[it]!!.add(widget)
                            }
                        }
                    }
                }
            }
            val scores = HashMap<WTGNode, Double>()
            allPossibleNodes.forEach {window ->
                val missStaticWidgets = window.widgets.filterNot { staticWidget -> matchWidgets[window]!!.values.any { it.contains(staticWidget) } }
                val totalWidgets = matchWidgets[window]!!.size + missStaticWidgets.size
                if (!window.fromModel && matchWidgets[window]!!.values.flatten().size/window.widgets.size.toDouble() < 0.7) {
                    //need match perfectly at least all widgets with resource id.
                    //Give a bound to be 70%
                    scores.put(window,Double.NEGATIVE_INFINITY)
                } else {
                    val score = if (matchWidgets[window]!!.size > 0 || window.widgets.size == 0) {
                        (matchWidgets[window]!!.size * 1.0 - missWidgets.size * 1.0) / totalWidgets
                    } else {
                        Double.NEGATIVE_INFINITY
                    }
                    scores.put(window, score)
                }
            }
            return scores
        }

        fun getVisibleInteractableWidgets(newState: State<*>) =
                getVisibleWidgets(newState).filter {
                    isInteractiveWidget(it)
                }

        fun getVisibleWidgets(state: State<*>) =
                state.widgets.filter { isVisibleWidget(it) }

        private fun isVisibleWidget(it: Widget) =
                it.enabled && (it.isVisible || it.visibleAreas.isNotEmpty()) && !it.isKeyboard

        fun getVisibleWidgetsForAbstraction(state: State<*>, packageName: String) =
                state.widgets.filter {
                    it.enabled
                            && it.isVisible
                            && it.visibleAreas.isNotEmpty()
                            && !it.isKeyboard
                            //&& !hasParentWithType(it, state, "WebView")
                            && isInteractiveWidget(it)
                            //&& it.packageName == packageName
                }

        fun getInputFields(state: State<*>) =
                Helper.getVisibleWidgets(state).filter { it.isInputField || it.checked.isEnabled() }

        fun  getStaticWidgets(originalWidget: Widget, state: State<*>, attributeValuationSet: AttributeValuationSet, wtgNode: WTGNode, updateModel: Boolean,
                             autAutMF: AutAutMF): List<StaticWidget> {
            var matchedStaticWidgets: ArrayList<StaticWidget> = ArrayList()
            val appName = autAutMF.getAppName()
            var widget = originalWidget
            if (widget.resourceId.isNotBlank()) {
                val unqualifiedResourceId = getUnqualifiedResourceId(widget)

                matchedStaticWidgets.addAll(wtgNode.widgets.filter {
                    if (widget.resourceId == "android:id/title") {
                        it.resourceIdName == unqualifiedResourceId && it.text == widget.text
                    } else {
                        it.resourceIdName == unqualifiedResourceId
                    }
                })
            }
            if (matchedStaticWidgets.isEmpty() && widget.contentDesc.isNotBlank()) {
                matchedStaticWidgets.addAll(wtgNode.widgets.filter { w ->
                    widget.contentDesc == w.contentDesc
                })
            }
            if (matchedStaticWidgets.isEmpty() && !widget.isInputField && widget.text.isNotBlank()) {
                matchedStaticWidgets.addAll(wtgNode.widgets.filter { w ->
                    w.possibleTexts.contains(widget.text)
                })
            }
            if (matchedStaticWidgets.isEmpty()) {
                matchedStaticWidgets.addAll(wtgNode.widgets.filter { w ->
                    w.xpath.equals(originalWidget.xpath)
                })
            }
            /*if (matchedStaticWidgets.isEmpty()
                    && (widget.className == "android.widget.RelativeLayout" || widget.className.contains("ListView") ||  widget.className.contains("RecycleView" ) ||  widget.className == "android.widget.LinearLayout"))
            {
                matchedStaticWidgets.addAll(wtgNode.widgets.filter { w ->
                    w.className.contains(widget.className) && w.resourceId.isBlank() && w.resourceIdName.isBlank()
                })
            }
            if (matchedStaticWidgets.isEmpty() &&
                    (hasParentWithType(widget, state, "ListView") || hasParentWithType(widget, state, "RecycleView"))) {
                //this is an item of ListView or RecycleView

            }*/
            if (updateModel) {
                matchedStaticWidgets.forEach {
                    if (originalWidget.isInputField && originalWidget.text.isNotBlank()) {
                        it.textInputHistory.add(originalWidget.text)
                    }
                }
                if (matchedStaticWidgets.isEmpty()) {
                    if (originalWidget.resourceId == "android:id/content") {
                        return matchedStaticWidgets
                    }
                    if (originalWidget.resourceId.isNotBlank()
                            || originalWidget.contentDesc.isNotBlank()) {
                        val newWidget = StaticWidget.getOrCreateStaticWidget(
                                widgetId = StaticWidget.getWidgetId(),
                                resourceIdName = getUnqualifiedResourceId(originalWidget.resourceId),
                                className = originalWidget.className,
                                wtgNode = wtgNode,
                                resourceId = "",
                                activity = wtgNode.activityClass
                        )
                        newWidget.contentDesc = originalWidget.contentDesc
                        if (originalWidget.resourceId == "android:id/title") {
                            newWidget.text = originalWidget.text
                        }
                        wtgNode.addWidget(newWidget)
                        matchedStaticWidgets.add(newWidget)
                    }
                }
            }
            return matchedStaticWidgets
        }

        fun updateInteractiveWidget(widget: Widget, state: State<*>, staticWidget: StaticWidget, wtgNode: WTGNode
                                    , isNewCreatedWidget: Boolean, autAutMF: AutAutMF) {
            val isInteractive: Boolean
            if (!isInteractiveWidget(widget)) {
                if (widget.className == "android.widget.ImageView" && hasParentWithType(widget, state, "Gallery")) {
                    isInteractive = true
                } else {
                    isInteractive = false
                }
            } else {
                isInteractive = true
            }
            wtgNode.widgetState[staticWidget] = isInteractive
            if (isNewCreatedWidget)
                staticWidget.interactive = isInteractive
//            if (!wtgNode.widgetState[staticWidget]!!)
//            {
//                regressionTestingMF.transitionGraph.edges(wtgNode).filter { it.label.widget == staticWidget }.forEach {
//                    regressionTestingMF.addDisablePathFromState(state,it.label,it.destination!!.data)
//                }
//            }
        }

        private fun shouldCreateNewWidget(widget: Widget) =
                (!widget.isKeyboard
                        && (widget.resourceId.isNotBlank() || isInteractiveWidget(widget)))

        fun getMappedGUIWidgets(visibleWidgets: List<Widget>, sourceNode: WTGNode): HashMap<Widget, StaticWidget> {
            val mappedGUIWidgets = HashMap<Widget, StaticWidget>()
            for (guiWidget in visibleWidgets) {
                for (widget in sourceNode.widgets) {
                    if (widget.containGUIWidget(guiWidget)) {
                        mappedGUIWidgets.put(guiWidget, widget)
                        break
                    }
                }
            }
            return mappedGUIWidgets
        }

        fun copyStaticWidgetAndItsEvents(staticWidget: StaticWidget, newNode: WTGNode, sourceNode: WTGNode
                                         , wtg: WindowTransitionGraph) {
            if (!newNode.widgets.contains(staticWidget))
                newNode.widgets.add(staticWidget)
            val relatedEdges = wtg.edges(sourceNode).filter {
                it.label.widget == staticWidget
            }
            relatedEdges.forEach {
                val relatedEvent = it.label
                if (it.destination?.data == sourceNode)
                    wtg.add(newNode, newNode, relatedEvent)
                else
                    wtg.add(newNode, it.destination?.data, relatedEvent)
            }
        }

        private fun moveStaticWidgetAndItsEvents(staticWidget: StaticWidget, newNode: WTGNode, sourceNode: WTGNode
                                                 , wtg: WindowTransitionGraph) {
            if (!newNode.widgets.contains(staticWidget))
                newNode.widgets.add(staticWidget)
            sourceNode.widgets.remove(staticWidget)
            val relatedEdges = wtg.edges(sourceNode).filter {
                it.label.widget == staticWidget
            }
            relatedEdges.forEach {
                wtg.add(newNode, it.destination?.data, it.label)
                wtg.update(sourceNode, it.destination?.data, WTGFakeNode(), it.label, it.label).also {
                    if (it != null && wtg.edgeProved.containsKey(it))
                        wtg.edgeProved.remove(it)

                }
            }

        }

        internal var changeRatioCriteria: Double = 0.05

        fun isInteractiveWidget(widget: Widget): Boolean =
                widget.enabled && ( widget.isInputField || widget.clickable || widget.checked != null || widget.longClickable || widget.scrollable || (!widget.hasClickableDescendant && widget.selected.isEnabled() && widget.selected == true) )

        fun hasParentWithType(it: Widget, state: State<*>, parentType: String): Boolean {
            var widget: Widget = it
            while (widget.hasParent) {
                val parent = state.widgets.find { w -> w.id == widget.parentId }
                if (parent != null) {
                    if (parent.className.contains(parentType)) {
                        return true
                    }
                    widget = parent
                } else {
                    return false
                }
            }
            return false
        }

        fun hasParentWithResourceId(widget: Widget, state: State<*>, parentIdPatterns: List<String>): Boolean {
            var w: Widget = widget
            while (w.hasParent) {
                val parent = state.widgets.find { it -> it.id == w.parentId }
                if (parent != null) {
                    if (parentIdPatterns.find { w.resourceId.contains(it) } != null) {
                        return true
                    }
                    w = parent
                } else {
                    return false
                }
            }
            return false
        }

        fun tryGetParentHavingResourceId(widget: Widget, currentState: State<*>): Widget {
            var parentWidget: Widget? = widget
            while (parentWidget != null) {
                if (parentWidget.resourceId.isNotBlank())
                    return parentWidget
                parentWidget = currentState.widgets.find {
                    it.idHash == parentWidget!!.parentHash
                }

            }
            return widget
        }

        fun getUnqualifiedResourceId(widget: Widget): String {
            val unqualifiedResourceId = widget.resourceId.substring(widget.resourceId.indexOf("/") + 1)
            return unqualifiedResourceId
        }

        fun getUnqualifiedResourceId(qualifiedResourceId: String): String {
            val unqualifiedResourceId = qualifiedResourceId.substring(qualifiedResourceId.indexOf("/") + 1)
            return unqualifiedResourceId
        }

        fun haveClickableChild(allWidgets: List<Widget>, parent: Widget): Boolean {
            val allChildren = ArrayList<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    allChildren.add(childWidget)
                    if (childWidget.clickable) {
                        return true
                    }
                }
            }
            allChildren.forEach {
                if (haveClickableChild(allWidgets,it)) {
                    return true
                }
            }
            return false
        }

        fun haveLongClickableChild(allWidgets: List<Widget>, parent: Widget): Boolean {
            val allChildren = ArrayList<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    allChildren.add(childWidget)
                    if (childWidget.longClickable) {
                        return true
                    }
                }
            }
            allChildren.forEach {
                if (haveClickableChild(allWidgets,it)) {
                    return true
                }
            }
            return false
        }

        fun haveScrollableChild(allWidgets: List<Widget>, parent: Widget): Boolean {
            val allChildren = ArrayList<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    allChildren.add(childWidget)
                    if (childWidget.scrollable) {
                        return true
                    }
                }
            }
            allChildren.forEach {
                if (haveClickableChild(allWidgets,it)) {
                    return true
                }
            }
            return false
        }

        fun getAllInteractiveChild(allWidgets: List<Widget>, parent: Widget): List<Widget> {
            val interactiveWidgets = arrayListOf<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    if (isInteractiveWidget(childWidget) && isVisibleWidget(childWidget)) {
                        interactiveWidgets.add(childWidget)
                    }
                    interactiveWidgets.addAll(getAllInteractiveChild(allWidgets, childWidget))
                }

            }
            return interactiveWidgets
        }

        fun getAllInteractiveChild2(allWidgets: List<Widget>, parent: Widget): List<Widget> {
            val interactiveWidgets = arrayListOf<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    if (childWidget.canInteractWith) {
                        interactiveWidgets.add(childWidget)
                    }
                    interactiveWidgets.addAll(getAllInteractiveChild2(allWidgets, childWidget))
                }

            }
            return interactiveWidgets
        }

        fun getAllChild(allWidgets: List<Widget>, parent: Widget): List<Widget> {
            val visibleWidgets = arrayListOf<Widget>()
            parent.childHashes.forEach {
                val childWidget = allWidgets.firstOrNull { w -> w.idHash == it }
                if (childWidget != null) {
                    if (childWidget.isVisible) {
                        visibleWidgets.add(childWidget)
                    }
                    visibleWidgets.addAll(getAllChild(allWidgets, childWidget))
                }

            }
            return visibleWidgets
        }

        fun computeGuiTreeDimension(guiState: State<*>): Rectangle {
            val outboundViews = guiState.widgets.filter { !it.hasParent && !it.isKeyboard }
            if (outboundViews.isNotEmpty()) {
                val outBound = outboundViews.maxBy { it.boundaries.height+it.boundaries.width }!!.boundaries
                return outBound
            }
            val bound = guiState.widgets.sortedBy { it.boundaries.width + it.boundaries.height }.last().boundaries
            return bound
        }

        fun computeGuiTreeVisibleDimension(guiState: State<*>): Rectangle {
            val outboundViews = guiState.widgets.filter { !it.hasParent && !it.isKeyboard }
            if (outboundViews.isNotEmpty()) {
                val outBound = outboundViews.maxBy { it.visibleBounds.height+it.visibleBounds.width }!!.visibleBounds
                return outBound
            }
            val bound = guiState.widgets.sortedBy { it.visibleBounds.width + it.visibleBounds.height }.last().visibleBounds
            return bound
        }

         fun parseSwipeData(data: String): List<Pair<Int, Int>> {
            val splitData = data.split(" TO ")
            if (splitData.size != 2) {
                return emptyList()
            }
            val first = splitData[0].split(",").let { Pair(first = it[0].toInt(),second = it[1].toInt())}
            val second = splitData[1].split(",").let { Pair(first = it[0].toInt(),second = it[1].toInt())}
            return arrayListOf(first,second)
        }

        fun computeStep(swipeInfo: List<Pair<Int, Int>>): Int {
            val dx = abs(swipeInfo[0].first-swipeInfo[1].first)
            val dy = abs(swipeInfo[0].second-swipeInfo[1].second)
            return (dx+dy)/2
        }

        fun parseCoordinationData(data: String): Pair<Int,Int> {
            val splitData = data.split(",")
            if (splitData.size == 2) {
                return Pair(splitData[0].toInt(),splitData[1].toInt())
            }
            return Pair(0,0)
        }

        fun extractInputFieldAndCheckableWidget(prevState: State<*>): Map<Widget,String> {
            val condition = HashMap<Widget, String>()
            prevState.visibleTargets.filter { it.isInputField }.forEach { widget ->
                condition.put(widget, widget.text)
            }
            prevState.visibleTargets.filter { it.checked.isEnabled() }.forEach { widget ->
                condition.put(widget, widget.checked.toString())
            }
            return condition
        }

        fun extractTextInputWidgetData(sourceNode: WTGNode, prevState: State<*>): HashMap<StaticWidget, String> {
            val inputTextData = HashMap<StaticWidget, String>()
            sourceNode.widgets.filter { it.isInputField }.forEach {
                val textInputWidget = prevState.widgets.find { w ->
                    it.containGUIWidget(w)
                }
                if (textInputWidget != null) {
                    inputTextData.put(it, textInputWidget.text)
                }
            }
            return inputTextData
        }
    }
}