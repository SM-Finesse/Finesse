import { describe, expect, it } from 'vitest'
import { addRecent, readRecent, removeRecent } from './recent'

describe('최근 검색 저장', () => {
  it('맨 앞에 넣고, 대소문자만 다른 같은 이름은 하나로 치며, 5명까지만 둔다', () => {
    let list: string[] = []
    for (const n of ['a01', 'b02', 'c03', 'd04', 'e05']) list = addRecent(list, n)
    list = addRecent(list, 'C03')
    expect(list).toEqual(['C03', 'e05', 'd04', 'b02', 'a01'])
    list = addRecent(list, 'f06')
    expect(list).toEqual(['f06', 'C03', 'e05', 'd04', 'b02'])
    expect(readRecent()).toEqual(list)
  })

  it('지운 이름은 저장소에서도 빠진다', () => {
    const list = removeRecent(addRecent(addRecent([], 'icly'), 'turtle'), 'ICLY')
    expect(list).toEqual(['turtle'])
    expect(readRecent()).toEqual(['turtle'])
  })

  it('저장값이 깨져 있으면 유효한 이름만 남기거나 빈 목록으로 시작한다', () => {
    localStorage.setItem('finesse.recent', JSON.stringify(['ok_name', 42, 'bad name!', 'OK_NAME', 'x']))
    expect(readRecent()).toEqual(['ok_name'])
    localStorage.setItem('finesse.recent', '{not json')
    expect(readRecent()).toEqual([])
  })
})
