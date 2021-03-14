$ ->
    $("#ticker").html new Date()
    setInterval ->
        $("#ticker").html new Date()
    , 1000

$ ->
    initialSliderVal = $("#savings-slider").val();
    $("#savings-amount").html "£" + ((initialSliderVal / 100) * $("#incoming-total").html()).toFixed(2);
    $("#savings-slider").on "input", (e) =>
        percent = e.target.value
        console.log percent
        $("#savings-slider-val").html percent
        $("#savings-slider-val-target").attr("href", "/plans/updateSavingsPayoffPercentage?percent=" + percent)
        $("#savings-amount").html "£" + ((percent / 100) * $("#incoming-total").html()).toFixed(2);
