import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";

import PluginLogoIcon from "./components/PluginLogoIcon.vue";
import TransformerView from "./views/TransformerView.vue";

import "./styles/main.scss";
import "uno.css";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "transformer",
        name: "Transformer",
        component: TransformerView,
        meta: {
          title: "页面转换器",
          description: "支持根据规则转换指定页面内容",
          searchable: true,
          permissions: ["plugin:transformer:manage"],
          menu: {
            name: "页面转换器",
            icon: markRaw(PluginLogoIcon),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
