# 本机工具链与运行期环境变量
# 用法：source tools/env.sh   （在仓库根目录执行；后续 mvn / npm / mysql 命令都靠它）
# 说明：本机 Homebrew 已损坏，JDK17 / Maven / Node 全部装在 tools/ 下，不污染系统环境。
#       真实凭据放在同目录 local-secret.env（已 gitignore），本文件只留默认值。

_TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

export JAVA_HOME="$_TOOLS_DIR/jdk17/Contents/Home"
export MAVEN_HOME="$_TOOLS_DIR/maven"
# /usr/local/bin 必须显式列出，不能只靠末尾的 $PATH 继承：
# 本机 /usr/bin/git 是坏的空壳（CommandLineTools 残缺，一调就报 xcrun: error: invalid active
# developer path），真正可用的 git 2.20.1 在 /usr/local/bin；而实测部分执行环境的 PATH 只有
# tools/node/bin:/usr/bin:/bin，光靠继承会让本计划里所有 git 命令失败。
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$_TOOLS_DIR/node/bin:/usr/local/mysql/bin:/usr/local/bin:$PATH"

# ---- 本机凭据（存在则加载，缺失不报错，方便新机器先跑起来）----
[ -f "$_TOOLS_DIR/local-secret.env" ] && source "$_TOOLS_DIR/local-secret.env"

# ---- MySQL（本机已装 8.0.46，服务在跑，库 script_workbench 已建）----
export DB_USER="${DB_USER:-root}"
export DB_PASS="${DB_PASS:-}"
export DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/script_workbench?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"

# ---- 阿里百炼 DashScope（业务空间专属 Key，sk-ws- 前缀）----
# 已实测：该 Key 在公共端点 dashscope.aliyuncs.com 上也能用，所以 base-url 保持默认即可；
# 若要改走业务空间专属 MaaS 端点，export AI_BASE_URL=https://ws-q6x7mi6uitc06swd.cn-beijing.maas.aliyuncs.com 后重启后端。
# AI_ENABLED=false 时后端自动降级：语法校验、占位符提取、沙箱运行照常可用，
# 只有 AI 审查 / AI 对话返回中文提示。单测统一走降级分支，不烧额度。
export AI_DASHSCOPE_API_KEY="${AI_DASHSCOPE_API_KEY:-not-configured}"
export AI_BASE_URL="${AI_BASE_URL:-https://dashscope.aliyuncs.com}"
export AI_CHAT_MODEL="${AI_CHAT_MODEL:-qwen-plus}"
export AI_ENABLED="${AI_ENABLED:-true}"
