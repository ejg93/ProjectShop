# 화면 컨테이너 이미지. 빌드 문맥은 `frontend/` 다(Railway 는 Root Directory 를 `frontend` 로).
#
# **왜 화면도 컨테이너인가**(`Q38`). 화면이 바깥 호스팅에 서면 백엔드는 공개 주소를 열어야 하고,
# 그러면 `TRUSTED_PROXIES` 를 좁힐 수가 없다 — 그 목록이 넓으면 아무나 `X-Forwarded-For` 로
# IP 를 속여서 `user_consent.acted_ip`(동의 입증)가 틀린 값으로 쌓인다(`D14`, `backend/README.md`).
# 둘을 같은 사설망에 두면 그 대역으로 좁힐 수 있고 **백엔드는 공개 도메인이 없어진다.**
#
# 두 단계다 — 빌드 단계는 소스와 개발 의존성을 들고, 실행 단계는 `standalone` 산출물만 든다.
# 로컬은 이 파일을 안 쓴다(`npm run dev`). 호스팅이 이 파일을 읽어 이미지를 만든다.
#
# Node 판은 CI 와 같은 22 다(`ci.yml`) — 갈리면 여기서만 나는 실패가 생긴다.
FROM node:22-alpine AS build
WORKDIR /src

# 의존성 설치를 소스보다 앞에 두면 소스만 바뀐 빌드에서 이 층이 캐시로 남는다.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 타입 검사·린트·테스트는 CI 가 돈다(`ci.yml`). 이미지 빌드는 산출물만 만든다.
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

# `standalone` 은 실행에 필요한 것만 추려 낸다 — `node_modules` 를 통째로 안 싣는다.
# 정적 파일 둘은 그 산출물에 안 들어가서 따로 옮긴다(Next 문서).
COPY --from=build /src/.next/standalone ./
COPY --from=build /src/.next/static ./.next/static
COPY --from=build /src/public ./public

# 호스팅이 주는 포트를 그대로 쓴다. 없으면 로컬과 같은 3000 이다.
ENV PORT=3000
EXPOSE 3000
# `standalone` 이 만드는 서버 진입점. `next start` 가 아니라 이것을 부른다.
CMD ["node", "server.js"]
