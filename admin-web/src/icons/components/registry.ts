export interface IconDefinition {
  width: number
  height: number
  viewBox: string
  data: string
}

const definitions: Record<string, IconDefinition> = {}

export function getIcon(name: string) {
  return definitions[name]
}

export default {
  register(icons: Record<string, IconDefinition>) {
    Object.assign(definitions, icons)
  }
}
