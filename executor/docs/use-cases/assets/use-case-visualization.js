(function () {
  const state = {
    scenarioIndex: 0,
    stepIndex: 0,
    started: false,
    playing: false,
    speed: 1,
    timer: null
  };

  function data() {
    return window.VIS_DATA;
  }

  function scenario() {
    return data().scenarios[state.scenarioIndex];
  }

  function step() {
    return scenario().steps[state.stepIndex];
  }

  function el(id) {
    return document.getElementById(id);
  }

  function stopPlayback() {
    if (state.timer) {
      clearTimeout(state.timer);
      state.timer = null;
    }
    state.playing = false;
  }

  function setStarted(started) {
    state.started = started;
    el('startOverlay').style.display = started ? 'none' : 'block';
    el('controlBar').style.display = started ? 'flex' : 'none';
  }

  function renderScenarioSelector() {
    const container = el('scenarioSelector');
    container.innerHTML = '';

    data().scenarios.forEach((item, index) => {
      const button = document.createElement('button');
      button.className = 'scenario-pill' + (index === state.scenarioIndex ? ' active' : '');
      button.textContent = item.name;
      button.onclick = function () {
        switchScenario(index);
      };
      container.appendChild(button);
    });
  }

  function renderPipeline() {
    const container = el('pipeline');
    container.innerHTML = '';

    scenario().steps.forEach((item, index) => {
      const stepNode = document.createElement('div');
      let className = 'pipeline-step';

      if (state.started && index < state.stepIndex) {
        className += ' completed';
      }
      if (state.started && index === state.stepIndex) {
        className += item.status === 'failed' ? ' failed active' : ' active';
      }

      stepNode.className = className;
      stepNode.onclick = function () {
        selectStep(index);
      };

      const circle = document.createElement('div');
      circle.className = 'step-circle';
      circle.textContent = String(index + 1);

      const label = document.createElement('div');
      label.className = 'step-label';
      label.textContent = item.label;

      stepNode.appendChild(circle);
      stepNode.appendChild(label);
      container.appendChild(stepNode);

      if (index < scenario().steps.length - 1) {
        const connector = document.createElement('div');
        connector.className = 'pipeline-connector';
        if (state.started && index < state.stepIndex) {
          connector.classList.add('filled');
        }
        if (state.started && item.status === 'failed' && index === state.stepIndex) {
          connector.classList.add('failed-line');
        }
        container.appendChild(connector);
      }
    });
  }

  function renderNarration() {
    el('scenarioTitle').textContent = scenario().name;
    el('scenarioDesc').innerHTML = scenario().description;
    el('stepTitle').textContent = step().title;
    el('narrationBody').innerHTML = step().narration;
  }

  function renderDbPanel() {
    const panel = el('dbPanel');
    panel.innerHTML = '';

    step().dbSections.forEach((section) => {
      const title = document.createElement('div');
      title.className = 'db-section-title';
      title.textContent = section.title;
      panel.appendChild(title);

      const table = document.createElement('table');
      table.className = 'db-table';

      section.fields.forEach((field) => {
        const row = document.createElement('tr');

        const key = document.createElement('td');
        key.className = 'db-key';
        key.textContent = field.key;

        const value = document.createElement('td');
        value.className = 'db-val';
        value.innerHTML = field.changed
          ? '<span class="changed">' + field.value + '</span>'
          : field.value;

        row.appendChild(key);
        row.appendChild(value);
        table.appendChild(row);
      });

      panel.appendChild(table);

      if (section.note) {
        const note = document.createElement('div');
        note.className = 'db-note';
        note.innerHTML = section.note;
        panel.appendChild(note);
      }
    });
  }

  function renderControls() {
    el('stepCounter').textContent = state.started
      ? (state.stepIndex + 1) + ' / ' + scenario().steps.length
      : '0 / ' + scenario().steps.length;
    el('playBtn').textContent = state.playing ? '⏸ 일시정지' : '▶ 재생';

    document.querySelectorAll('.speed-btn').forEach((button) => {
      button.classList.toggle('active', Number(button.dataset.speed) === state.speed);
    });
  }

  function escapeHtml(text) {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function highlightCode(code) {
    let html = escapeHtml(code);
    const tokens = [];

    function stash(value, className) {
      const key = '__TOK' + String.fromCharCode(65 + tokens.length) + '__';
      tokens.push('<span class="' + className + '">' + value + '</span>');
      return key;
    }

    html = html.replace(/(\/\/.*)$/gm, function (match) {
      return stash(match, 'tok-comment');
    });

    html = html.replace(/(@[A-Za-z_][A-Za-z0-9_]*)/g, function (match) {
      return stash(match, 'tok-annotation');
    });

    html = html.replace(/("([^"\\]|\\.)*")/g, function (match) {
      return stash(match, 'tok-string');
    });

    html = html.replace(/\b(public|private|protected|static|final|class|interface|enum|void|if|else|return|new|true|false|for|while|switch|case|break|continue|try|catch|throw)\b/g, '<span class="tok-keyword">$1</span>');
    html = html.replace(/\b(String|Integer|Long|Boolean|ExecutionJob|ExecutionJobStatus|LocalDateTime|JsonNode|HttpEntity|HttpMethod|RuntimeException|ExecutorJobDispatchCommand|ExecutorJobExecuteCommand|BuildCallback)\b/g, '<span class="tok-type">$1</span>');
    html = html.replace(/\b(\d+)\b/g, '<span class="tok-number">$1</span>');
    html = html.replace(/\b([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=\()/g, '<span class="tok-method">$1</span>');
    html = html.replace(/\b(jobPort|dispatchService|notifyPort|notifyStartedPort|notifyCompletedPort|jenkinsQueryPort|jenkinsTriggerPort|receiveJobUseCase|handleStartedUseCase|handleCompletedUseCase|properties|objectMapper|json|callback|job|cmd)\b/g, '<span class="tok-field">$1</span>');

    tokens.forEach(function (token, index) {
      const key = '__TOK' + String.fromCharCode(65 + index) + '__';
      html = html.replaceAll(key, token);
    });

    return html;
  }

  function highlightAllCodeBlocks() {
    document.querySelectorAll('.code-card pre').forEach((block) => {
      block.innerHTML = highlightCode(block.textContent);
    });
  }

  function renderAll() {
    renderScenarioSelector();
    renderPipeline();
    renderNarration();
    renderDbPanel();
    renderControls();
  }

  function nextStepInternal() {
    if (state.stepIndex >= scenario().steps.length - 1) {
      stopPlayback();
      renderControls();
      return;
    }
    state.stepIndex += 1;
    renderAll();
    if (state.playing) {
      scheduleNext();
    }
  }

  function scheduleNext() {
    stopPlayback();
    state.playing = true;
    renderControls();
    state.timer = setTimeout(nextStepInternal, 1800 / state.speed);
  }

  window.startSimulation = function () {
    state.stepIndex = 0;
    setStarted(true);
    renderAll();
  };

  window.switchScenario = function (index) {
    stopPlayback();
    state.scenarioIndex = index;
    state.stepIndex = 0;
    setStarted(false);
    renderAll();
  };

  window.selectStep = function (index) {
    if (!state.started) {
      setStarted(true);
    }
    stopPlayback();
    state.stepIndex = index;
    renderAll();
  };

  window.prevStep = function () {
    if (!state.started) {
      setStarted(true);
      renderAll();
      return;
    }
    stopPlayback();
    state.stepIndex = Math.max(0, state.stepIndex - 1);
    renderAll();
  };

  window.nextStep = function () {
    if (!state.started) {
      setStarted(true);
      renderAll();
      return;
    }
    stopPlayback();
    nextStepInternal();
  };

  window.togglePlay = function () {
    if (!state.started) {
      setStarted(true);
    }

    if (state.playing) {
      stopPlayback();
      renderControls();
      return;
    }

    scheduleNext();
  };

  window.setSpeed = function (speed) {
    state.speed = speed;
    if (state.playing) {
      scheduleNext();
    } else {
      renderControls();
    }
  };

  document.addEventListener('DOMContentLoaded', function () {
    setStarted(false);
    renderAll();
    highlightAllCodeBlocks();
  });
})();
