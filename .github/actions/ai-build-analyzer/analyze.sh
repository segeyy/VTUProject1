#!/bin/bash
ANDROID_ROOT=$1
BUILD_TYPE=$2
GRADLE_VERSION=$3

# Đường dẫn file log
LOG_FILE="$GITHUB_WORKSPACE/$ANDROID_ROOT/build/outputs/logs/build.log"

# Kiểm tra file log tồn tại
if [ ! -f "$LOG_FILE" ]; then
  echo "::warning::Log file not found at $LOG_FILE"
  exit 0
fi

# Trích xuất 100 dòng lỗi cuối
ERROR_LOG=$(tail -n 100 "$LOG_FILE")

# Tạo prompt cho AI
read -r -d '' PROMPT << EOM
[INST] <<SYS>>
Bạn là chuyên gia Android CI/CD. Hãy phân tích log build sau:
1. Chỉ ra nguyên nhân chính (tiếng Việt)
2. Đề xuất 1-2 cách khắc phục ngắn gọn
3. Phiên bản Gradle: $GRADLE_VERSION
<</SYS>>

$ERROR_LOG
[/INST]
EOM

# Gọi Hugging Face API
RESPONSE=$(curl -s https://api-inference.huggingface.co/models/bigcode/starcoder \
  -H "Authorization: Bearer ${HF_TOKEN}" \
  -d "{\"inputs\":\"${PROMPT}\", \"parameters\":{\"max_new_tokens\":200}}")

# Kiểm tra và xử lý response
if [[ $(echo $RESPONSE | jq 'has("error")') == "true" ]]; then
  ERROR_MSG=$(echo $RESPONSE | jq -r '.error')
  echo "::warning::Hugging Face API error: $ERROR_MSG"
  exit 0
fi

ANALYSIS=$(echo $RESPONSE | jq -r '.[0].generated_text')

# Hiển thị kết quả
echo "::group::🤖 AI Build Failure Analysis"
echo "${ANALYSIS}"
echo "::endgroup::"

# Comment lên PR nếu có
if [[ "$GITHUB_EVENT_NAME" == "pull_request" ]]; then
  PR_NUMBER=$(jq --raw-output .pull_request.number "$GITHUB_EVENT_PATH")
  curl -s -X POST \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
    -d "{\"body\":\"❌ **Build Failed Analysis** (Gradle $GRADLE_VERSION)\\n\\n${ANALYSIS}\"}"
fi
