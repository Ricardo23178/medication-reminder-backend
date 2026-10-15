import test from 'node:test'
import assert from 'node:assert/strict'
import { validateReason, validateSetting } from '../src/validation.js'

test('实际字典对象可提交，缺失固定键、数组或过长标签会被拒绝', () => {
  const setting = { category: 'DICT', code: 'event.labels' }
  const value = { UNCONFIRMED_TIMEOUT: '超时未确认', CONSECUTIVE_UNCONFIRMED: '连续未确认', PLAN_ENDED_UNFINISHED: '计划结束仍有未完成任务' }
  assert.equal(validateSetting(setting, JSON.stringify(value)), '')
  assert.ok(validateSetting(setting, '[]'))
  assert.ok(validateSetting(setting, JSON.stringify({ ...value, UNCONFIRMED_TIMEOUT: '字'.repeat(41) })))
  assert.ok(validateSetting(setting, JSON.stringify({ UNCONFIRMED_TIMEOUT: '超时' })))
})

test('模板长度与实际投递参数边界在提交前校验', () => {
  const template = { category: 'TEMPLATE' }
  assert.equal(validateSetting(template, JSON.stringify({ title: '用药提醒', content: '${nickname}，请查看异常。' })), '')
  assert.ok(validateSetting(template, JSON.stringify({ title: '字'.repeat(81), content: '正文' })))
  assert.ok(validateSetting(template, JSON.stringify({ title: '标题', content: '字'.repeat(501) })))
  assert.equal(validateSetting({ category: 'PARAM', code: 'delivery.batch-size' }, '100'), '')
  assert.ok(validateSetting({ category: 'PARAM', code: 'delivery.batch-size' }, '101'))
  assert.ok(validateSetting({ category: 'PARAM', code: 'delivery.batch-size' }, '1.5'))
  assert.equal(validateSetting({ category: 'PARAM', code: 'delivery.paused' }, 'false'), '')
  assert.ok(validateSetting({ category: 'PARAM', code: 'delivery.paused' }, '0'))
  assert.ok(validateReason('  '))
  assert.equal(validateReason('调整配置'), '')
})
