-- coverfo 💬 아침 브리핑을 카톡 '나에게 보내기'로 받기 (v336) — Supabase SQL Editor 에 전체를 붙여넣고 Run 1회
-- 카카오 키(토큰)는 서버(service_role)만 읽고 쓴다(정책 없음 → 화면에서는 못 읽음). 계정을 탈퇴하면 함께 지워진다.
-- 기존 표·함수·결제 쪽은 건드리지 않습니다.
create table if not exists public.cf_kakao_memo (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  refresh_token text not null,            -- 카카오 리프레시 토큰 (접근 키를 새로 받을 때)
  access_token  text,                     -- 카카오 접근 키 (몇 시간마다 새로 받음)
  access_exp    timestamptz,
  refresh_exp   timestamptz,
  is_on         boolean not null default true,   -- 브리핑을 카톡으로 받기 켜짐
  last_error    text,                     -- 마지막으로 보내지 못한 이유 (화면에 안내)
  updated_at    timestamptz not null default now()
);
alter table public.cf_kakao_memo enable row level security;
