export function validateReason(reason) {
  const length = [...reason.trim()].length
  return length >= 3 && length <= 200 ? '' : '操作原因须为 3～200 字，不能仅含空格'
}

export function validateSetting(setting, value) {
  if (setting.category === 'PARAM') {
    if (setting.code === 'delivery.paused') return ['true', 'false'].includes(value.trim()) ? '' : '暂停开关只能填写 true 或 false'
    if (setting.code === 'delivery.batch-size') return /^(?:[1-9]\d?|100)$/.test(value.trim()) ? '' : '每批数量须为 1～100 的整数'
    return '未识别的参数，请刷新后重试'
  }
  let parsed
  try { parsed = JSON.parse(value) } catch { return '请输入合法 JSON，字符串使用英文双引号' }
  if (setting.category === 'DICT') {
    const keys = ['UNCONFIRMED_TIMEOUT', 'CONSECUTIVE_UNCONFIRMED', 'PLAN_ENDED_UNFINISHED']
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' && Object.keys(parsed).length === 3 && keys.every(key => typeof parsed[key] === 'string' && parsed[key].trim() && [...parsed[key]].length <= 40)
      ? '' : '字典须为包含三个固定异常编码的 JSON 对象，每个名称为 1～40 字'
  }
  if (setting.category === 'TEMPLATE') {
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' && typeof parsed.title === 'string' && parsed.title.trim() && [...parsed.title].length <= 80 && typeof parsed.content === 'string' && parsed.content.trim() && [...parsed.content].length <= 500 && Object.keys(parsed).every(key => ['title', 'content'].includes(key))
      ? '' : '模板须只含非空 title（最多 80 字）、content（最多 500 字）字符串'
  }
  return '未识别的配置类型，请刷新后重试'
}
