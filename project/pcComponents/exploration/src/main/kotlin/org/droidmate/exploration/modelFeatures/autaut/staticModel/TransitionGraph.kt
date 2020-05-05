package org.droidmate.exploration.modelFeatures.autaut.staticModel

import org.droidmate.exploration.modelFeatures.graph.*
import org.droidmate.exploration.modelFeatures.autaut.*
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class TransitionGraph(private val graph: IGraph<WTGNode, StaticEvent> =
                              Graph(WTGNode("Root", 0.toString()),
                                      stateComparison = { a, b -> a == b },
                                      labelComparison = { a, b ->
                                        a==b
                                      })): IGraph<WTGNode, StaticEvent> by graph{
    val edgeConditions: HashMap<Edge<*,*>,HashMap<StaticWidget,String>> = HashMap()
    val edgeProved: HashMap<Edge<*,*>, Int> = HashMap()
    val statementCoverageInfo: HashMap<Edge<*,*>,ArrayList<String>> = HashMap()
    val methodCoverageInfo: HashMap<Edge<*,*>,ArrayList<String>> = HashMap()

    fun constructFromJson(jObj: JSONObject){
        var jMap = jObj.getJSONObject("allActivityNodes")
        jMap.keys().asSequence().forEach { key ->
            val windowInfo = StaticAnalysisJSONFileHelper.windowParser(jMap[key]!! as String)
            //val sourceNode = getOrCreateWTGNode(windowInfo)
        }

         jMap = jObj.getJSONObject("allTransitions")
        //for each Activity transition
        jMap.keys().asSequence().forEach { key ->
            val source = key as String
            log.debug("source: $source")
            val windowInfo = StaticAnalysisJSONFileHelper.windowParser(source)
            val sourceNode = getOrCreateWTGNode(windowInfo)
            if (sourceNode is WTGLauncherNode)
            {
                this.add(root.data,sourceNode, LaunchAppEvent(sourceNode))
            }
            //for each possbile transition to another window
            val transitions = jMap[key] as JSONArray
            transitions.forEach { it ->
                val transition = it as JSONObject
                var ignoreWidget = false
                val action = transition["action"] as String
                if (!StaticEvent.isIgnoreEvent(action))
                {
                    if (StaticEvent.isNoWidgetEvent(action)
                    )
                    {
                        ignoreWidget = true
                    }

                    val target = transition["target"] as String
                    log.debug("action: $action")
                    log.debug("target: $target")
                    val targetInfo = StaticAnalysisJSONFileHelper.windowParser(target)
                    val targetNode = getOrCreateWTGNode(targetInfo)
                    if (targetNode is WTGOptionsMenuNode || sourceNode is WTGLauncherNode)
                    {
                        ignoreWidget = true
                    }
                    val staticWidget: StaticWidget?

                    if (ignoreWidget == false)
                    {
                        val targetView = transition["widget"] as String
                        log.info("parsing widget: $targetView")
                        val widgetInfo =  StaticAnalysisJSONFileHelper.widgetParser(targetView)
                        staticWidget = StaticWidget.getOrCreateStaticWidget(widgetId = widgetInfo["id"]!!,
                                resourceId = widgetInfo["resourceId"]!!,
                                resourceIdName = widgetInfo["resourceIdName"]!!,
                                className = widgetInfo["className"]!!
                                , wtgNode = sourceNode
                                ,activity = sourceNode.classType)
                    }
                    else
                    {
                        staticWidget = null
                    }
                    val event: StaticEvent
                    event = StaticEvent(EventType.valueOf(action), arrayListOf(), staticWidget, sourceNode.classType,sourceNode)
                    edgeConditions.put(this.add(sourceNode,targetNode,event), HashMap())

                }

                //construct graph
            }
        }
        WTGOptionsMenuNode.allNodes.forEach {o ->
            val owner = WTGActivityNode.allNodes.find { a -> a.classType == o.classType }
            if (owner != null)
            {
                val edges = this.edges(owner,o)
                if (edges.isEmpty())
                {
                    this.add(owner,o, StaticEvent(eventType = EventType.implicit_menu,
                            eventHandlers = ArrayList(),
                            widget = null,
                            activity = o.classType,
                            sourceWindow = o))
                }
            }

        }

    }

    fun getOrCreateWTGNode(windowInfo: HashMap<String, String>): WTGNode {
            val wtgNode = when (windowInfo["NodeType"]) {
                "ACT" -> WTGActivityNode.getOrCreateNode(windowInfo["id"]!!, windowInfo["className"]!!)
                "DIALOG" -> WTGDialogNode.getOrCreateNode(windowInfo["id"]!!, windowInfo["className"]!!)
                "OptionsMenu" -> WTGOptionsMenuNode.getOrCreateNode(windowInfo["id"]!!, windowInfo["className"]!!)
                "ContextMenu" -> WTGContextMenuNode.getOrCreateNode(windowInfo["id"]!!, windowInfo["className"]!!)
                "LAUNCHER_NODE" -> WTGLauncherNode.getOrCreateNode()
                else -> WTGNode(windowInfo["id"]!!, windowInfo["className"]!!)
            }
            if (wtgNode is WTGOptionsMenuNode)
            {
                val activityNode = WTGActivityNode.allNodes.find{it.classType == wtgNode.classType}
                if (activityNode!=null)
                {
                    if (this.edges(activityNode,wtgNode).isEmpty())
                        this.add(activityNode,wtgNode, StaticEvent(EventType.implicit_menu, ArrayList(), null, activityNode.classType, sourceWindow = activityNode))
                }

            }
            return wtgNode
    }

        fun getNextRoot(): WTGNode?{
            return this.edges(root).firstOrNull()?.destination?.data
        }
        fun haveContextMenu(wtgNode: WTGNode): Boolean{
            val outEdges = this.edges(wtgNode)
            if (outEdges.find { it.destination != null && it.destination!!.data is WTGContextMenuNode }!=null)
                return true
            return false
        }

        fun haveOptionsMenu(wtgNode: WTGNode): Boolean{
            val outEdges = this.edges(wtgNode)
            if (outEdges.find { it.destination != null && it.destination!!.data is WTGOptionsMenuNode && it.label.eventType!= EventType.implicit_back_event}!=null)
                return true
            if (outEdges.find { it.destination != null && it.label.eventType== EventType.implicit_menu}!=null)
                return true
            return false
        }

        fun getOptionsMenu(wtgNode: WTGNode): WTGNode? {
            if (wtgNode is WTGOptionsMenuNode) {
                return null
            }
            val edges = this.edges(wtgNode).filter {
                it.destination != null
                        && it.destination!!.data is WTGOptionsMenuNode
                        &&  it.label.eventType!= EventType.implicit_back_event }
            return edges.map { it.destination!!.data }.firstOrNull()
        }

        fun getContextMenus(wtgNode: WTGNode): List<WTGContextMenuNode>{
            val edges = this.edges(wtgNode).filter { it.destination != null}
                    .filter { it.destination!!.data is WTGContextMenuNode }
            return edges.map { it.destination!!.data as WTGContextMenuNode }.toMutableList()
        }

        fun getDialogs(wtgNode: WTGNode): List<WTGDialogNode> {
            val edges = this.edges(wtgNode).filter { it.destination != null}
                    .filter { it.destination!!.data is WTGDialogNode }
            return edges.map { it.destination!!.data as WTGDialogNode }.toMutableList()
        }

        fun getWindowBackward(wtgNode: WTGNode): List<Edge<*, *>>{
            val pressBackEvents = arrayListOf<Edge<*, *>>()
            this.edges(wtgNode).filter { it.label.eventType == EventType.implicit_back_event }.forEach {
                pressBackEvents.add(it)
            }
            return pressBackEvents
        }

        fun containsGraph(childGraph: TransitionGraph): Boolean{
            val childGraphRoot = childGraph.getNextRoot()
            if (childGraphRoot == null)
                return false
            var node:WTGNode? = childGraphRoot
            val stack: Stack<Edge<WTGNode, StaticEvent>> = Stack()
            val traversedEdge = arrayListOf<Edge<WTGNode, StaticEvent>>()
            while (node!=null)
            {
                val edges = childGraph.edges(node)
                val newEdges = edges.filter { !traversedEdge.contains(it)  }
                if (newEdges.isEmpty())
                {
                    //return before
                    if (stack.empty())
                        break
                    val prevEdge = stack.pop()
                    node = prevEdge.source.data
                }
                else
                {
                    val validEdge = newEdges.first()
                    if (this.edge(validEdge.source.data,validEdge.destination?.data,validEdge.label)==null)
                    {
                        return false
                    }
                    traversedEdge.add(validEdge)
                    stack.push(validEdge)
                    node = validEdge.destination?.data
                }
            }
            return true
        }

    fun mergeNode(source: WTGNode, dest: WTGNode) {
        source.widgets.forEach {
            //source.widgets.remove(it)
            if (!dest.widgets.contains(it)) {
               dest.addWidget(it)
            }
        }
        source.widgets.clear()

        val edges = this.edges(source).toMutableList()
        edges.forEach {
            it.label.activity = dest.classType
            it.label.sourceWindow = dest
            this.add(dest, it.destination?.data, it.label)
        }

        this.getVertices().forEach { v ->
            val outEdges = this.edges(v)
            outEdges.filter { it.destination!=null && it.destination!!.data == source }.forEach { e ->
              update(v.data,e.destination?.data,dest,e.label,e.label)
            }
        }
    }

    fun copyNode(source: WTGNode, dest: WTGNode) {
        source.widgets.forEach {
            if (!dest.widgets.contains(it)) {
                dest.addWidget(it)
            }
        }

        val edges = this.edges(source).toMutableList()
        edges.forEach {
            it.label.activity = dest.classType
            it.label.sourceWindow = dest
            this.add(dest, it.destination?.data, it.label)
        }

    }
    fun getUnknownNodes(activityNode: WTGActivityNode): List<WTGNode> {
        val edges = this.edges(activityNode).filter { it.destination != null && it.label.eventType!= EventType.implicit_back_event}
                .filter { it.destination!!.data is WTGAppStateNode }.toMutableList()
        return edges.map { it.destination!!.data as WTGAppStateNode }
    }

    override fun add(source: WTGNode, destination: WTGNode?, label: StaticEvent, updateIfExists: Boolean, weight: Double): Edge<WTGNode, StaticEvent> {
        val edge = graph.add(source, destination, label, updateIfExists, weight)
        edgeProved.put(edge,0)
        edgeConditions.put(edge, HashMap())
        methodCoverageInfo.put(edge, ArrayList())
        statementCoverageInfo.put(edge,ArrayList())
        return edge
    }

    companion object {

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(TransitionGraph::class.java) }


    }
}