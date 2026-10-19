import { defineComponent, h } from 'vue'
import { getIcon } from './components/registry'

export default defineComponent({
  name: 'SvgIcon',
  props: {
    name: {
      type: String,
      required: true
    },
    width: {
      type: [String, Number],
      default: '1em'
    },
    height: {
      type: [String, Number],
      default: '1em'
    }
  },
  setup(props) {
    return () => {
      const icon = getIcon(props.name)
      if (!icon) {
        throw new Error(`Unknown SVG icon: ${props.name}`)
      }
      return h('svg', {
        class: 'svg-icon',
        viewBox: icon.viewBox,
        width: props.width,
        height: props.height,
        'aria-hidden': 'true',
        innerHTML: icon.data
      })
    }
  }
})
