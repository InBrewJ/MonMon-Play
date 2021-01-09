$ ->
    $("#ticker").html new Date()
    setInterval ->
        $("#ticker").html new Date()
    , 1000

$ ->
    $("#savings-slider").on "input", (e) =>
        $("#savings-slider-val").html e.target.value;
        $("#savings-amount").html "£" + ((e.target.value / 100) * $("#incoming-total").html()).toFixed(2);
