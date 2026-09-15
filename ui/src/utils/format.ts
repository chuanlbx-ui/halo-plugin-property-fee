/**
 * 楼栋/单元显示统一格式化。
 *
 * 背景：房屋数据的 building 字段本身通常已带「栋」（如 1栋 / A栋），unit 字段也常带
 * 「单元」（如 1单元），但也可能是「商业区」这类非数字名。旧代码一律再拼一个单位字，
 * 于是出现「1栋栋101」「商业区栋」「1单元单元」这类错位显示。
 * 规则：只有纯数字才补单位字，其余原样输出。
 */
function withSuffix(value: unknown, suffix: string): string {
  const v = String(value ?? '').trim()
  if (!v) return '-'
  return /^\d+$/.test(v) ? `${v}${suffix}` : v
}

/** 楼栋：1 → 1栋；1栋 → 1栋；商业区 → 商业区；空 → - */
export function fmtBuilding(building?: string | null): string {
  return withSuffix(building, '栋')
}

/** 单元：1 → 1单元；1单元 → 1单元；空 → '' */
export function fmtUnit(unit?: string | null): string {
  const v = String(unit ?? '').trim()
  if (!v) return ''
  return withSuffix(v, '单元')
}
