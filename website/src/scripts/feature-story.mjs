function initFeatureStory() {
  const story = document.querySelector('.more-features');
  const pin = story?.querySelector('.story-pin');
  const steps = [...(story?.querySelectorAll('[data-story-step]') ?? [])];
  const links = [...(story?.querySelectorAll('[data-story-link]') ?? [])];
  const media = [...(story?.querySelectorAll('[data-story-media]') ?? [])];
  const clips = [...(story?.querySelectorAll('video') ?? [])];
  if (
    !pin ||
    !steps.length ||
    steps.length !== links.length ||
    steps.length !== media.length ||
    !window.matchMedia
  )
    return;
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  const space = window.matchMedia(
    '(min-width:760px) and (min-height:650px), (min-width:360px) and (max-width:759px) and (min-height:800px)',
  );
  let frame = 0;
  const played = new WeakSet();

  clips.forEach((clip) => {
    const button = clip.parentElement.querySelector('[data-clip-toggle]');
    if (!button) return;
    function syncToggle() {
      button.dataset.paused = String(clip.paused);
      button.setAttribute(
        'aria-label',
        clip.paused ? button.dataset.playLabel : button.dataset.pauseLabel,
      );
    }
    button.addEventListener('click', () => {
      if (clip.paused) {
        played.add(clip);
        clip.play().catch(() => undefined);
      } else clip.pause();
    });
    clip.addEventListener('play', syncToggle);
    clip.addEventListener('pause', syncToggle);
    clip.controls = false;
    button.hidden = false;
    syncToggle();
  });

  function pauseClip(clip) {
    if (!clip.paused) played.delete(clip);
    clip.pause();
  }

  function pauseClips() {
    clips.forEach(pauseClip);
  }

  function syncClips() {
    clips.forEach((clip) => {
      const panel = clip.closest('[data-story-media]');
      const bounds = clip.getBoundingClientRect();
      const visible = bounds.height > 0 && bounds.top >= 0 && bounds.bottom <= innerHeight;
      if (document.hidden || !visible || panel.getAttribute('aria-hidden') === 'true') {
        pauseClip(clip);
      } else if (
        story.classList.contains('story-ready') &&
        !reduced.matches &&
        panel.dataset.active === 'true' &&
        !played.has(clip)
      ) {
        played.add(clip);
        clip.play().catch(() => undefined);
      }
    });
  }

  function select(active) {
    if (story.dataset.step === String(active)) return;
    steps.forEach((step, index) => {
      step.dataset.active = String(index === active);
      links[index].setAttribute('aria-current', String(index === active));
    });
    media.forEach((panel, index) => {
      panel.dataset.active = String(index === active);
      panel.inert = index !== active;
      panel.setAttribute('aria-hidden', String(index !== active));
    });
    story.dataset.step = String(active);
  }

  function render() {
    frame = 0;
    if (document.hidden) {
      pauseClips();
      return;
    }
    const bounds = story.getBoundingClientRect();
    if (bounds.bottom < 0 || bounds.top > window.innerHeight) {
      pauseClips();
      return;
    }
    if (story.classList.contains('story-ready')) {
      const inset = parseFloat(getComputedStyle(pin).top) || 0;
      const range = Math.max(1, story.offsetHeight - pin.offsetHeight);
      const position = Math.max(
        0,
        Math.min(steps.length, ((inset - bounds.top) / range) * steps.length),
      );
      const active = Math.min(steps.length - 1, Math.floor(position));
      select(active);
      story.style.setProperty('--story-progress', String(position - active));
    }
    syncClips();
  }

  function schedule() {
    if (!frame && !document.hidden && (story.classList.contains('story-ready') || clips.length))
      frame = requestAnimationFrame(render);
  }

  function configure() {
    pauseClips();
    const enabled = !reduced.matches && space.matches;
    story.style.setProperty('--story-count', String(steps.length));
    story.classList.toggle('story-ready', enabled);
    if (!enabled) {
      media.forEach((panel) => {
        panel.removeAttribute('aria-hidden');
        panel.removeAttribute('data-active');
        panel.inert = false;
      });
      steps.forEach((step) => step.removeAttribute('data-active'));
      links.forEach((link) => link.removeAttribute('aria-current'));
      delete story.dataset.step;
    } else {
      select(0);
      render();
    }
  }

  reduced.addEventListener('change', configure);
  space.addEventListener('change', configure);
  window.addEventListener('scroll', schedule, { passive: true });
  window.addEventListener('resize', schedule, { passive: true });
  window.addEventListener('pageshow', configure);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) pauseClips();
    else schedule();
  });
  links.forEach((link, index) => {
    link.addEventListener('click', (event) => {
      if (
        !story.classList.contains('story-ready') ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      )
        return;
      event.preventDefault();
      const inset = parseFloat(getComputedStyle(pin).top) || 0;
      const range = Math.max(1, story.offsetHeight - pin.offsetHeight);
      const top = scrollY + story.getBoundingClientRect().top - inset;
      window.scrollTo({ top: top + (range * (index + 0.5)) / steps.length, behavior: 'instant' });
      schedule();
    });
  });
  configure();
}

if (typeof document !== 'undefined' && typeof window !== 'undefined') initFeatureStory();
