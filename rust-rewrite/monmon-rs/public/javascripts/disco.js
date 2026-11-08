(function () {
  'use strict';

  var canvas, ctx;
  var sparkles = [];
  var animFrameId = null;
  var isRunning = false;
  var maxSparkles = 50;

  var DISCO_COLORS = [
    '#ffffff', // Diamond white
    '#fde047', // Champagne gold
    '#67e8f9', // Electric cyan
    '#f472b6', // Hot pink
    '#c084fc', // Neon purple
    '#34d399', // Mint emerald
    '#fed7aa'  // Warm peach
  ];

  function shouldActivateDisco() {
    var slot = document.getElementById('disco-header-slot');
    var isPayday = slot && slot.getAttribute('data-is-payday') === 'true';

    // URL override (?disco=true or ?disco=false)
    var urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('disco') === 'true') {
      try { sessionStorage.setItem('monmon-disco', 'on'); } catch (e) {}
      return true;
    }
    if (urlParams.get('disco') === 'false') {
      try { sessionStorage.setItem('monmon-disco', 'off'); } catch (e) {}
      return false;
    }

    // User preference in sessionStorage
    try {
      var saved = sessionStorage.getItem('monmon-disco');
      if (saved === 'on') return true;
      if (saved === 'off') return false;
    } catch (e) {}

    return isPayday;
  }

  function Sparkle(w, h, originX, originY) {
    this.reset(w, h, originX, originY, true);
  }

  Sparkle.prototype.reset = function (w, h, originX, originY, randomStart) {
    this.x = randomStart ? Math.random() * w : (originX + (Math.random() - 0.5) * 80);
    this.y = randomStart ? Math.random() * h : (originY + Math.random() * 20);
    this.size = 2 + Math.random() * 3.5;
    this.color = DISCO_COLORS[Math.floor(Math.random() * DISCO_COLORS.length)];
    this.alpha = randomStart ? Math.random() * 0.9 : 0.05;
    this.phase = Math.random() * Math.PI * 2;
    this.twinkleSpeed = 0.03 + Math.random() * 0.04;
    this.rotation = Math.random() * Math.PI;
    this.rotSpeed = (Math.random() - 0.5) * 0.03;

    // Gentle outward radiating drifting velocity
    var angle = Math.random() * Math.PI * 2;
    var speed = 0.2 + Math.random() * 0.6;
    this.vx = Math.cos(angle) * speed;
    this.vy = Math.sin(angle) * speed * 0.7 + 0.15; // slightly downward drift
  };

  Sparkle.prototype.update = function (w, h, originX, originY) {
    this.phase += this.twinkleSpeed;
    this.alpha = 0.15 + (Math.sin(this.phase) * 0.5 + 0.5) * 0.8;
    this.x += this.vx;
    this.y += this.vy;
    this.rotation += this.rotSpeed;

    if (this.x < -20 || this.x > w + 20 || this.y < -20 || this.y > h + 20 || this.phase > Math.PI * 8) {
      this.reset(w, h, originX, originY, false);
    }
  };

  Sparkle.prototype.draw = function (ctx) {
    ctx.save();
    ctx.translate(this.x, this.y);
    ctx.rotate(this.rotation);
    ctx.globalAlpha = Math.max(0, Math.min(1, this.alpha));

    // Soft radiant glow halo
    var grad = ctx.createRadialGradient(0, 0, 0, 0, 0, this.size * 2.2);
    grad.addColorStop(0, this.color);
    grad.addColorStop(1, 'rgba(255, 255, 255, 0)');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(0, 0, this.size * 2.2, 0, Math.PI * 2);
    ctx.fill();

    // 4-pointed diamond star sparkle
    ctx.beginPath();
    var s = this.size * 1.5;
    ctx.moveTo(-s, 0);
    ctx.quadraticCurveTo(0, 0, 0, -s);
    ctx.quadraticCurveTo(0, 0, s, 0);
    ctx.quadraticCurveTo(0, 0, 0, s);
    ctx.quadraticCurveTo(0, 0, -s, 0);
    ctx.fillStyle = '#ffffff';
    ctx.fill();

    ctx.restore();
  };

  function getDiscoBallOrigin() {
    var slot = document.getElementById('disco-header-slot');
    if (slot) {
      var rect = slot.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    }
    return { x: window.innerWidth / 2, y: 30 };
  }

  function resizeCanvas() {
    if (!canvas) return;
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
  }

  function loop() {
    if (!isRunning) return;
    var w = canvas.width;
    var h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    var origin = getDiscoBallOrigin();
    for (var i = 0; i < sparkles.length; i++) {
      sparkles[i].update(w, h, origin.x, origin.y);
      sparkles[i].draw(ctx);
    }
    animFrameId = requestAnimationFrame(loop);
  }

  function startSparkles() {
    if (isRunning) return;
    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.id = 'disco-sparkles-canvas';
      document.body.appendChild(canvas);
      ctx = canvas.getContext('2d');
      window.addEventListener('resize', resizeCanvas);
    }
    resizeCanvas();
    sparkles = [];
    var origin = getDiscoBallOrigin();
    for (var i = 0; i < maxSparkles; i++) {
      sparkles.push(new Sparkle(canvas.width, canvas.height, origin.x, origin.y));
    }
    canvas.style.display = 'block';
    isRunning = true;
    animFrameId = requestAnimationFrame(loop);
  }

  function stopSparkles() {
    isRunning = false;
    if (animFrameId) {
      cancelAnimationFrame(animFrameId);
      animFrameId = null;
    }
    if (canvas) {
      canvas.style.display = 'none';
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }
  }

  function initSnackbars(dangerDay) {
    if (document.getElementById('payday-snackbar-container')) return;

    var container = document.createElement('div');
    container.id = 'payday-snackbar-container';
    document.body.appendChild(container);

    var resolvedDay = dangerDay && dangerDay.trim() !== '' ? dangerDay.trim() : '25th';

    var messages = [
      { id: 'shop', icon: '🛒', text: "don't shop when you have loads of cash" },
      { id: 'danger', icon: '⏳', text: "loads of money now isn't loads of money on the " + resolvedDay },
      { id: 'calm', icon: '🌱', text: "be calm, think of the future" }
    ];

    var dismissed = {};
    try {
      var saved = sessionStorage.getItem('monmon-dismissed-snackbars');
      if (saved) dismissed = JSON.parse(saved);
    } catch (e) {}

    messages.forEach(function (msg, idx) {
      if (dismissed[msg.id]) return;

      setTimeout(function () {
        if (!container.parentElement) return;

        var bar = document.createElement('div');
        bar.className = 'payday-snackbar';
        bar.setAttribute('data-msg-id', msg.id);

        var icon = document.createElement('div');
        icon.className = 'payday-snackbar-icon';
        icon.textContent = msg.icon;

        var text = document.createElement('div');
        text.className = 'payday-snackbar-text';
        text.textContent = msg.text;

        var closeBtn = document.createElement('button');
        closeBtn.className = 'payday-snackbar-close';
        closeBtn.type = 'button';
        closeBtn.innerHTML = '&times;';
        closeBtn.setAttribute('aria-label', 'Dismiss notification');

        closeBtn.addEventListener('click', function () {
          bar.style.animation = 'snackbarSlideOut 0.25s forwards';
          dismissed[msg.id] = true;
          try {
            sessionStorage.setItem('monmon-dismissed-snackbars', JSON.stringify(dismissed));
          } catch (e) {}
          setTimeout(function () {
            if (bar.parentElement) {
              bar.parentElement.removeChild(bar);
            }
          }, 250);
        });

        bar.appendChild(icon);
        bar.appendChild(text);
        bar.appendChild(closeBtn);
        container.appendChild(bar);
      }, idx * 400 + 300);
    });
  }

  function removeSnackbars() {
    var container = document.getElementById('payday-snackbar-container');
    if (container && container.parentElement) {
      container.parentElement.removeChild(container);
    }
  }

  function setDiscoMode(active, savePref) {
    var slot = document.getElementById('disco-header-slot');
    if (slot) {
      slot.style.display = active ? 'flex' : 'none';
    }
    if (active) {
      startSparkles();
      var dangerDay = slot ? slot.getAttribute('data-danger-day') : '25th';
      initSnackbars(dangerDay);
      if (savePref) {
        try { sessionStorage.setItem('monmon-disco', 'on'); } catch (e) {}
      }
    } else {
      stopSparkles();
      removeSnackbars();
      if (savePref) {
        try { sessionStorage.setItem('monmon-disco', 'off'); } catch (e) {}
      }
    }
  }

  function init() {
    var slot = document.getElementById('disco-header-slot');
    if (!slot) return;

    var active = shouldActivateDisco();
    setDiscoMode(active, false);

    slot.addEventListener('click', function () {
      var nextState = !isRunning;
      setDiscoMode(nextState, true);
    });

    document.addEventListener('visibilitychange', function () {
      if (document.hidden) {
        if (isRunning && animFrameId) {
          cancelAnimationFrame(animFrameId);
          animFrameId = null;
        }
      } else if (isRunning && !animFrameId) {
        animFrameId = requestAnimationFrame(loop);
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
