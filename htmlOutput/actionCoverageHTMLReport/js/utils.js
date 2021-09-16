
function drawWidget(actionData) {
    var boundsX = actionData.targetWidget.visibleBounds.leftX;
    var boundsY = actionData.targetWidget.visibleBounds.topY;
    var boundsWidth = actionData.targetWidget.visibleBounds.width;
    var boundsHeight = actionData.targetWidget.visibleBounds.height;

    // https://stackoverflow.com/questions/53810434/crop-the-image-using-javascript
    var imageUrl = actionData.prevImage;
    const image = new Image();
    image.onload = function () {
        const canvas = document.getElementById("widget_canvas")

        // draw our image at position 0, 0 on the canvas
        const ctx = canvas.getContext("2d");
        if (boundsWidth < canvas.width) {
            var destWidth = boundsWidth
            var destHeight = boundsHeight
        } else {
            var destWidth = canvas.width;
            var destHeight = canvas.height;
        }
        if (boundsWidth > boundsHeight) {
            var scaleRatio = boundsWidth/destWidth;
            destHeight = boundsHeight/scaleRatio;
        }
        canvas.width = destWidth + 20
        canvas.height = destHeight + 20
        ctx.drawImage(image, boundsX, boundsY, boundsWidth, boundsHeight, 10, 10, destWidth, destHeight);
        // ctx.drawImage(image, 10, 10,400,200,10,10,400,200);
    };
    image.src = imageUrl

    //         var image = "screenshot/14.jpg"
    // 		var style = "width: " + boundsWidth + "px;" +
    // 			"height: " + boundsHeight + "px;" +
    // 			"display: block;"
    //             + "object-position: -"+boundsX+"px -"+boundsY+"px;"
    // 			+ "max-height: auto;" 
    // 			+ "max-width: auto;" 
    // 			// "background-repeat: no-repeat;"
    //             ;
    // 		var viewImg = "<div><img class=\"image-fluid\" src=\""+image+"\" style=\"" + style + "\" /></div>";
    //         container.html(viewImg)
}

$(document).ready(function () {
    loadData();
    $(window).resize(function () {
        redrawWidgetImage();
    });
});


function redrawWidgetImage() {
    var actionId = $('#actionId').text()
    var actionData = data[actionId]
    if (actionData.targetWidget != null) {
        drawWidget(actionData);
    }
}
function loadData() {
    var actionId = $('#actionId').text()
    loadDataByActionId(actionId)
}

function loadDataByActionId(actionId) {
    var actionData = data[actionId]
    $("#action-id").text(actionData.actionId)
    $("#action-type").text(actionData.actionType)
    if (actionData.targetWidget != null) {
        $("#triggered-widget-id").text(actionData.targetWidget.id)
        $('#triggered-widget-classname').text(actionData.targetWidget.className)
        $('#triggered-widget-text').text(actionData.targetWidget.text)
        drawWidget(actionData);
    }
    $("#source-state-id").text(actionData.from)
    $("#resulting-state-id").text(actionData.to)
    $("#action-data").text(actionData.data)
    $("#executed-statement-cnt").text(actionData.executedStatements)
    $("#executed-updated-instruction-cnt").text(actionData.executedUpdatedStatements)
    $("#new-executed-updated-instruction-cnt").text(actionData.newExecutedUpdatedStatements)
    const image = document.getElementById("image")
    image.src = actionData.image

    const prevImage = document.getElementById("prevImage")
    prevImage.src = actionData.prevImage
}