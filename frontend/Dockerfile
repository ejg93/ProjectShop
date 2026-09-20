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

# **백엔드 주소는 런타임이 아니라 여기서 받는다**(`Q116`, 사용자 선택 ①).
#
# `next.config.ts` 의 `rewrites()` 가 이 값을 목적지로 쓰는데, Next 가 그것을
# **빌드 산출물에 굳힌다**(`.next/routes-manifest.json`·`required-server-files.json`).
# `standalone` 은 설정 파일을 안 지고 그 산출물만 지므로 **호스팅에서 환경변수로 줘도 안 바뀐다.**
# 안 주고 빌드하면 이미지가 `http://localhost:8080` 을 지고 나가고, 배포된 화면의
# 브라우저 호출이 전부 자기 컨테이너를 때려 500 이 된다 — 페이지는 떠서 성공처럼 보인다.
#
# Railway 는 선언한 `ARG` 에 같은 이름의 서비스 변수를 빌드 때 넣어 준다.
ARG BACKEND_ORIGIN
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN

COPY . .
# 타입 검사·린트·테스트는 CI 가 돈다(`ci.yml`). 이미지 빌드는 산출물만 만든다.
RUN npm run build

# **굳은 목적지가 준 값과 같은지 여기서 잰다**(`Q116` 의 강제 지점).
#
# 위 `ARG` 는 **안 줘도 빌드가 지나간다** — 그러면 기본값이 굳어서 고치기 전과 같아진다.
# 그래서 산출물을 열어 확인하고, 아니면 **이미지 빌드를 세운다.** 재는 자리가 빌드 안이라
# 로컬 `npm run build` 는 영향을 안 받는다(거기는 기본값이 맞다).
RUN node -e "\
const m=require('./.next/routes-manifest.json');\
const want=process.env.BACKEND_ORIGIN;\
const got=(m.rewrites.afterFiles||[]).map(r=>r.destination).join(' ');\
if(!want){console.error('BACKEND_ORIGIN 을 빌드 인자로 안 줬다. --build-arg BACKEND_ORIGIN=http://<백엔드>:8080 (Q116)');process.exit(1);}\
if(!got.includes(want)){console.error('굳은 목적지가 준 값과 다르다. 준 값: '+want+' / 산출물: '+got+' (Q116)');process.exit(1);}\
console.log('프록시 목적지가 굳었다: '+got);"

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

# `standalone` 은 실행에 필요한 것만 추려 낸다 — `node_modules` 를 통째로 안 싣는다.
# 정적 파일 둘은 그 산출물에 안 들어가서 따로 옮긴다(Next 문서).
COPY --from=build /src/.next/standalone ./
COPY --from=build /src/.next/static ./.next/static
COPY --from=build /src/public ./public

# **`BACKEND_ORIGIN` 은 런타임에도 줘야 한다**(`Q116` 실측). 위 빌드 인자는 브라우저가 쓰는
# 프록시 목적지를 굳히는 것이고, **서버가 직접 부르는 자리**(`api.ts`·`api-session.ts`)는
# `process.env` 를 그때그때 읽는다 — 런타임 값이 없으면 그쪽이 로컬 주소로 가서
# **화면이 500 이다**(브라우저 경로는 멀쩡해서 절반만 깨진 것으로 보인다).
# Railway 는 서비스 변수 하나가 빌드와 런타임에 다 들어가므로 적는 자리는 한 곳이다.
#
# 호스팅이 주는 포트를 그대로 쓴다. 없으면 로컬과 같은 3000 이다.
ENV PORT=3000
EXPOSE 3000
# `standalone` 이 만드는 서버 진입점. `next start` 가 아니라 이것을 부른다.
CMD ["node", "server.js"]
