export const sizes = [
  { id: 'one', name: '1寸', width: 25, height: 35 },
  { id: 'two', name: '2寸', width: 35, height: 49 },
  { id: 'three', name: '3寸', width: 55, height: 84 },
  { id: 'small-one', name: '小1寸', width: 22, height: 32 },
  { id: 'large-one', name: '大1寸', width: 33, height: 48 },
  { id: 'small-two', name: '小2寸', width: 35, height: 45 },
  { id: 'large-two', name: '大2寸', width: 35, height: 53 },
  { id: 'four', name: '4寸', width: 76, height: 102 }
] as const

export function resolveSize(id: unknown) {
  return sizes.find(size => size.id === id)
}
