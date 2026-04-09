/**
 * why: 配置页里的原生下拉框很多，统一在“先点中，再滚轮”时切换选项，
 * 既减少重复模板代码，也避免鼠标只是路过时误切换下拉值。
 */
export function updateSelectByWheel(event: WheelEvent) {
  const select = event.currentTarget;
  if (!(select instanceof HTMLSelectElement) || select.disabled) {
    return;
  }

  if (document.activeElement !== select) {
    return;
  }

  const direction = Math.sign(event.deltaY);
  if (direction === 0) {
    return;
  }

  const enabledOptions = Array.from(select.options).filter((option) => !option.disabled);
  if (enabledOptions.length <= 1) {
    return;
  }

  const currentIndex = enabledOptions.findIndex((option) => option.value === select.value);
  const fallbackIndex = currentIndex >= 0 ? currentIndex : 0;
  const nextIndex = Math.min(
    enabledOptions.length - 1,
    Math.max(0, fallbackIndex + (direction > 0 ? 1 : -1)),
  );

  if (nextIndex === fallbackIndex) {
    return;
  }

  event.preventDefault();
  select.value = enabledOptions[nextIndex].value;
  select.dispatchEvent(new Event("change", { bubbles: true }));
}
