#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fucksh 双端发布同步脚本 (GitHub Releases + CNB Releases + Git 镜像)

功能特性：
1. 提取 app/src/main/java/com/fucksh/data/RuleRepository.kt 中的内置规则，导出 rules.json 制品。
2. 收集/校验构建生成的 APK 产物，计算 SHA-256 与文件大小。
3. 生成并增量维护 dist/update.json 清单，提供 CNB 主通道与 GitHub 备用下载通道。
4. 自动同步发布至 GitHub Releases（创建 Release、上传 APK/rules.json/update.json）。
5. 自动同步发布至 CNB Releases：
   - 目标版本 Release（如 v1.0.0）
   - stable 稳定频道固定 Release（覆盖同名资产，长效免换地址）
   - update 自动更新固定 Release（同名覆盖，客户端固定抓取地址）
6. 自动将本地 Git 仓库镜像推送至 CNB Git 远端仓库。
7. 凭据完全依赖环境变量 `CNB_TOKEN` 与 `GH_TOKEN` (或 `GITHUB_TOKEN`)，严禁硬编码。

用法：
  python3 tools/release.py --version-name 1.0.0 --version-code 261 --dry-run
  python3 tools/release.py --version-name 1.0.0 --version-code 261 --skip-build
  python3 tools/release.py --build-only
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import urllib.error
import urllib.parse
import urllib.request

# 常量定义
CNB_API_BASE = "https://api.cnb.cool"
DEFAULT_GITHUB_REPO = "xingranya/fucksh"
DEFAULT_CNB_REPO = "xingranya/fucksh"
DEFAULT_CNB_GIT_URL = "https://cnb.cool/xingranya/fucksh.git"

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DIST_DIR = PROJECT_ROOT / "dist"
RULE_REPO_FILE = PROJECT_ROOT / "app/src/main/java/com/fucksh/data/RuleRepository.kt"
APP_GRADLE_FILE = PROJECT_ROOT / "app/build.gradle.kts"


def log(msg: str, level: str = "INFO") -> None:
    timestamp = datetime.now().strftime("%H:%M:%S")
    prefix = f"[{timestamp}] [{level}]"
    print(f"{prefix} {msg}", flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def extract_rules_from_repo(rule_file: Path) -> Dict[str, Any]:
    """从 RuleRepository.kt 源码中解析规则列表"""
    if not rule_file.is_file():
        raise FileNotFoundError(f"找不到规则文件: {rule_file}")

    content = rule_file.read_text(encoding="utf-8")

    # 提取 VERSION 常量
    version_match = re.search(r'const\s+val\s+VERSION\s*=\s*"([^"]+)"', content)
    version = version_match.group(1) if version_match else datetime.now().strftime("%Y.%m.%d")

    # 提取所有 Rule(...) 项
    pattern = re.compile(
        r'Rule\s*\(\s*"([^"]+)"\s*,\s*PolicyKey\.([A-Z_]+)\s*,\s*LogCategory\.([A-Z_]+)\s*\)'
    )
    matches = pattern.findall(content)

    rules = []
    for suffix, policy, category in matches:
        rules.append({
            "suffix": suffix,
            "policy": policy,
            "category": category,
            "enabled": policy != "NET_DOH"
        })

    # 生成标准北京时间带时区的 ISO 8601 字符串
    tz_cst = timezone(timedelta(hours=8))
    now_cst = datetime.now(tz_cst).replace(microsecond=0).isoformat()

    return {
        "version": version,
        "updatedAt": now_cst,
        "count": len(rules),
        "rules": rules
    }


def parse_gradle_version(gradle_file: Path) -> Tuple[int, str]:
    """从 app/build.gradle.kts 中解析默认 versionCode 与 versionName"""
    if not gradle_file.is_file():
        return 1, "1.0.0"
    content = gradle_file.read_text(encoding="utf-8")
    vc_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    code = int(vc_match.group(1)) if vc_match else 1
    name = vn_match.group(1) if vn_match else "1.0.0"
    return code, name


def run_gradle_build() -> Path:
    """调用带文件锁的构建脚本打包 Release APK"""
    log("开始执行 Gradle 构建 (assembleRelease)...")
    build_script = PROJECT_ROOT / "tmp/gradle-locked.sh"
    cmd = [
        str(build_script) if build_script.is_file() else str(PROJECT_ROOT / "gradlew"),
        ":app:assembleRelease",
        "--no-daemon",
        "--console=plain"
    ]
    res = subprocess.run(cmd, cwd=PROJECT_ROOT)
    if res.returncode != 0:
        log("assembleRelease 失败，尝试 assembleDebug 作为备选...", "WARN")
        cmd[1] = ":app:assembleDebug"
        res2 = subprocess.run(cmd, cwd=PROJECT_ROOT)
        if res2.returncode != 0:
            raise RuntimeError("Gradle 构建完全失败！")

    # 定位产物
    release_dir = PROJECT_ROOT / "app/build/outputs/apk/release"
    debug_dir = PROJECT_ROOT / "app/build/outputs/apk/debug"

    apks = list(release_dir.glob("*.apk")) if release_dir.is_dir() else []
    if not apks:
        apks = list(debug_dir.glob("*.apk")) if debug_dir.is_dir() else []

    if not apks:
        raise FileNotFoundError("构建完成但未在 outputs/apk 目录下找到生成的 APK 文件！")

    # 优先选取非 unsigned 的 apk
    signed_apks = [a for a in apks if "-unsigned" not in a.name]
    target_apk = signed_apks[0] if signed_apks else apks[0]
    log(f"构建产物定位成功: {target_apk.name} ({target_apk.stat().st_size} 字节)")
    return target_apk


def generate_manifest(
    apk_file: Path,
    version_name: str,
    version_code: int,
    notes: List[str],
    github_repo: str,
    cnb_repo: str,
) -> Dict[str, Any]:
    """生成或更新 update.json"""
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    update_file = DIST_DIR / "update.json"

    history: List[Dict[str, Any]] = []
    if update_file.is_file():
        try:
            existing = json.loads(update_file.read_text(encoding="utf-8"))
            if isinstance(existing.get("versions"), list):
                history = [v for v in existing["versions"] if v.get("versionCode") != version_code]
        except Exception as e:
            log(f"读取已有 update.json 失败，将重新初始化: {e}", "WARN")

    tz_cst = timezone(timedelta(hours=8))
    now_cst = datetime.now(tz_cst).replace(microsecond=0).isoformat()
    apk_size = apk_file.stat().st_size
    apk_sha256 = sha256_file(apk_file)

    primary_url = f"https://cnb.cool/{cnb_repo}/-/releases/download/update/fucksh-latest.apk"
    backup_url = f"https://github.com/{github_repo}/releases/download/v{version_name}/fucksh-v{version_name}.apk"

    changelog_str = f"v{version_name} ({version_code})\n\n" + "\n".join(f"- {n}" for n in notes)

    current_entry = {
        "versionName": version_name,
        "versionCode": version_code,
        "publishedAt": now_cst,
        "size": apk_size,
        "notes": notes,
        "sha256": apk_sha256,
        "apkUrl": primary_url,
        "backupApkUrl": backup_url
    }

    full_versions = [current_entry] + history

    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "publishedAt": now_cst,
        "size": apk_size,
        "sha256": apk_sha256,
        "apkUrl": primary_url,
        "backupApkUrl": backup_url,
        "changelog": changelog_str,
        "rulesUrl": f"https://cnb.cool/{cnb_repo}/-/releases/download/update/rules.json",
        "versions": full_versions
    }

    return manifest


# ==============================================================================
# CNB OpenAPI 客户端实现 (基于官方 Swagger 规范与 CLI 源码提取)
# ==============================================================================

class CnbClient:
    def __init__(self, token: str, repo: str, dry_run: bool = False):
        self.token = token
        self.repo = repo
        self.dry_run = dry_run
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.cnb.api+json",
            "Content-Type": "application/json"
        }

    def _request(self, method: str, path: str, data: Optional[Dict[str, Any]] = None, query: Optional[Dict[str, Any]] = None) -> Any:
        url = f"{CNB_API_BASE}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)

        body_bytes = json.dumps(data).encode("utf-8") if data is not None else None
        req = urllib.request.Request(url, data=body_bytes, headers=self.headers, method=method)

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_bytes = resp.read()
                if not resp_bytes:
                    return {}
                return json.loads(resp_bytes.decode("utf-8"))
        except urllib.error.HTTPError as e:
            err_msg = e.read().decode("utf-8", errors="replace")
            # 过滤敏感 Token 输出
            sanitized_err = re.sub(r'Bearer\s+[A-Za-z0-9_\-\.]+', 'Bearer ***', err_msg)
            raise RuntimeError(f"CNB API 错误 [{e.code}] {method} {path}: {sanitized_err}") from e

    def get_release_by_tag(self, tag: str) -> Optional[Dict[str, Any]]:
        if self.dry_run:
            log(f"[Dry-Run] 模拟查询 CNB Release (tag={tag}) -> 返回 None")
            return None
        try:
            encoded_tag = urllib.parse.quote(tag, safe="")
            res = self._request("GET", f"/{self.repo}/-/releases/tags/{encoded_tag}")
            return res
        except RuntimeError as e:
            if "[404]" in str(e) or "not found" in str(e).lower() or "errcode\":5" in str(e):
                return None
            raise

    def create_or_update_release(
        self,
        tag: str,
        title: str,
        body: str,
        target_commitish: str = "main",
        make_latest: str = "true"
    ) -> Dict[str, Any]:
        log(f"查询 CNB Release (tag={tag})...")
        existing = self.get_release_by_tag(tag)
        if existing and "id" in existing:
            release_id = existing["id"]
            log(f"CNB Release 已存在 (id={release_id})，执行更新...")
            if self.dry_run:
                log(f"[Dry-Run] 模拟更新 Release {release_id} (tag={tag})")
                return existing
            payload = {
                "name": title,
                "body": body,
                "make_latest": make_latest
            }
            return self._request("PATCH", f"/{self.repo}/-/releases/{release_id}", data=payload)
        else:
            log(f"创建 CNB Release (tag={tag})...")
            if self.dry_run:
                log(f"[Dry-Run] 模拟创建 Release (tag={tag}, title={title})")
                return {"id": "dry-run-release-id", "tag_name": tag}
            payload = {
                "tag_name": tag,
                "name": title,
                "body": body,
                "target_commitish": target_commitish,
                "make_latest": make_latest
            }
            return self._request("POST", f"/{self.repo}/-/releases", data=payload)

    def upload_asset(self, release_id: str, asset_name: str, file_path: Path, overwrite: bool = True) -> None:
        """执行 CNB 官方三步资产上传：获取预签名 URL -> PUT 文件 -> 确认上传完成"""
        file_size = file_path.stat().st_size
        log(f"CNB 准备上传资产: {asset_name} ({file_size} 字节, release_id={release_id})")

        if self.dry_run:
            log(f"[Dry-Run] 模拟上传资产: {asset_name} -> CNB Release {release_id}")
            return

        # 步骤 1: 获取预签名上传地址
        form = {
            "asset_name": asset_name,
            "overwrite": overwrite,
            "size": file_size,
            "ttl": 0
        }
        res = self._request("POST", f"/{self.repo}/-/releases/{release_id}/asset-upload-url", data=form)
        upload_url = res.get("upload_url")
        verify_url = res.get("verify_url")

        if not upload_url or not verify_url:
            raise RuntimeError(f"CNB 未返回有效的 upload_url / verify_url: {res}")

        # 步骤 2: PUT 二进制内容至预签名 URL
        log(f"正在上传数据至 CNB 存储...")
        with open(file_path, "rb") as f:
            data = f.read()

        put_headers = {
            "Content-Type": "application/octet-stream",
            "Content-Length": str(len(data))
        }
        put_req = urllib.request.Request(upload_url, data=data, headers=put_headers, method="PUT")
        with urllib.request.urlopen(put_req, timeout=120) as put_resp:
            if put_resp.status not in (200, 201, 204):
                raise RuntimeError(f"PUT 上传失败，HTTP 状态码: {put_resp.status}")

        # 步骤 3: 解析 verify_url 并调用确认接口
        # 典型 verify_url:
        # /{repo}/-/releases/{release_id}/asset-upload-confirmation/{upload_token}/{asset_path}
        # 或含域名的完整 URL
        log(f"向 CNB 确认附件上传完成...")
        v_path = urllib.parse.urlparse(verify_url).path
        confirm_path = v_path if v_path.startswith("/") else f"/{v_path}"
        self._request("POST", confirm_path, query={"ttl": 0})
        log(f"CNB 资产上传并关联完成: {asset_name}")


# ==============================================================================
# GitHub Release 与 Git 镜像
# ==============================================================================

def publish_github_release(
    tag: str,
    title: str,
    notes_file: Path,
    assets: List[Path],
    repo: str,
    dry_run: bool = False
) -> None:
    """使用 gh cli 或安全调用发布 GitHub Release"""
    log(f"准备发布 GitHub Release (tag={tag}, repo={repo})...")
    cmd = [
        "gh", "release", "create", tag,
        "--repo", repo,
        "--title", title,
        "--notes-file", str(notes_file)
    ]
    for asset in assets:
        cmd.append(str(asset))

    if dry_run:
        log(f"[Dry-Run] 模拟执行 GitHub Release 命令: {' '.join(cmd)}")
        return

    res = subprocess.run(cmd)
    if res.returncode != 0:
        log(f"gh release create 失败，尝试以覆盖/更新模式追加资产...", "WARN")
        cmd_upload = ["gh", "release", "upload", tag, "--repo", repo, "--clobber"] + [str(a) for a in assets]
        res2 = subprocess.run(cmd_upload)
        if res2.returncode != 0:
            raise RuntimeError("GitHub Release 发布失败！")
    log(f"GitHub Release 发布成功: {tag}")


def push_git_mirror_to_cnb(cnb_git_url: str, token: str, dry_run: bool = False) -> None:
    """使用带有凭据的临时认证 URL 将本地 Git 镜像推送到 CNB"""
    log("准备将当前 Git 仓库镜像同步至 CNB...")
    parsed = urllib.parse.urlparse(cnb_git_url)
    # CNB git 认证固定为 cnb:<token>
    auth_netloc = f"cnb:{token}@{parsed.netloc}"
    auth_url = urllib.parse.urlunparse((
        parsed.scheme,
        auth_netloc,
        parsed.path,
        parsed.params,
        parsed.query,
        parsed.fragment
    ))

    cmd = ["git", "push", auth_url, "HEAD:refs/heads/main", "--force", "--tags"]

    if dry_run:
        # 日志输出时脱敏
        safe_cmd = ["git", "push", f"https://cnb:***@{parsed.netloc}{parsed.path}", "HEAD:refs/heads/main", "--force", "--tags"]
        log(f"[Dry-Run] 模拟执行 Git 推送: {' '.join(safe_cmd)}")
        return

    # 捕获并严格脱敏任何可能包含 Token 的 stderr 输出
    try:
        proc = subprocess.run(
            cmd,
            cwd=PROJECT_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        if proc.returncode != 0:
            clean_err = proc.stderr.replace(token, "***")
            raise RuntimeError(f"Git 推送至 CNB 失败: {clean_err}")
        log("Git 仓库已成功同步至 CNB！")
    except Exception as e:
        clean_err = str(e).replace(token, "***")
        raise RuntimeError(f"Git 推送至 CNB 异常: {clean_err}") from None


# ==============================================================================
# 主流程
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(description="fucksh GitHub + CNB 同步发布工具")
    parser.add_argument("--version-name", type=str, help="版本名称 (例如 1.0.0)")
    parser.add_argument("--version-code", type=int, help="版本号 (单调递增整型，例如 261)")
    parser.add_argument("--apk", type=str, help="指定现有的 APK 文件路径 (跳过 Gradle 构建)")
    parser.add_argument("--skip-build", action="store_true", help="跳过构建，直接使用 outputs 下已有 APK")
    parser.add_argument("--build-only", action="store_true", help="仅执行构建并生成 update.json/rules.json，不执行远端发布")
    parser.add_argument("--dry-run", action="store_true", help="模拟运行所有流程，不发送实际网络写操作与发布变更")
    parser.add_argument("--github-repo", type=str, default=DEFAULT_GITHUB_REPO, help=f"GitHub 仓库标识 (默认 {DEFAULT_GITHUB_REPO})")
    parser.add_argument("--cnb-repo", type=str, default=DEFAULT_CNB_REPO, help=f"CNB 仓库标识 (默认 {DEFAULT_CNB_REPO})")
    parser.add_argument("--notes", type=str, nargs="*", help="本次版本的更新说明条目")

    args = parser.parse_args()

    # 1. 确定版本号
    def_code, def_name = parse_gradle_version(APP_GRADLE_FILE)
    version_name = args.version_name or def_name
    version_code = args.version_code or (261 if def_code < 261 else def_code)
    tag_name = f"v{version_name}"

    log(f"==================================================")
    log(f"fucksh 发布配置: tag={tag_name}, versionName={version_name}, versionCode={version_code}")
    log(f"==================================================")

    # 2. 获取构建产物 APK
    if args.apk:
        apk_path = Path(args.apk).resolve()
        if not apk_path.is_file():
            raise FileNotFoundError(f"指定的 APK 文件不存在: {apk_path}")
    elif args.skip_build:
        release_dir = PROJECT_ROOT / "app/build/outputs/apk/release"
        debug_dir = PROJECT_ROOT / "app/build/outputs/apk/debug"
        candidates = list(release_dir.glob("*.apk")) if release_dir.is_dir() else []
        if not candidates and debug_dir.is_dir():
            candidates = list(debug_dir.glob("*.apk"))
        if not candidates:
            raise FileNotFoundError("未找到现有 APK，请先执行构建或传入 --apk")
        apk_path = candidates[0]
        log(f"使用已有 APK: {apk_path}")
    else:
        apk_path = run_gradle_build()

    # 3. 提取规则库并写入 rules.json
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    rules_data = extract_rules_from_repo(RULE_REPO_FILE)
    rules_json_path = DIST_DIR / "rules.json"
    rules_json_path.write_text(json.dumps(rules_data, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"已导出规则包: {rules_json_path} (共 {rules_data['count']} 条内置规则)")

    # 4. 生成 update.json 清单
    default_notes = [
        "针对 ColorOS / 一加 16 深度适配 SystemHelper 双层拦截与隐私守护",
        "首页状态面板 Miuix 高保真重塑：状态胶囊、盾徽、三项防御指标",
        "核心防护模块 2×2 卡片交互接入与维保环境自检/重启守护",
        "关于页完整社区开源致谢与规则库自检反馈",
        "原生 compose-miuix-ui 规范适配，支持深浅色无缝自适应"
    ]
    notes = args.notes if args.notes else default_notes
    manifest = generate_manifest(
        apk_file=apk_path,
        version_name=version_name,
        version_code=version_code,
        notes=notes,
        github_repo=args.github_repo,
        cnb_repo=args.cnb_repo,
    )
    update_json_path = DIST_DIR / "update.json"
    update_json_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"已生成版本清单: {update_json_path}")

    # 归一化复制一份规整命名版 APK 到 dist/
    normalized_apk = DIST_DIR / f"fucksh-{tag_name}.apk"
    latest_apk = DIST_DIR / "fucksh-latest.apk"
    with open(apk_path, "rb") as src, open(normalized_apk, "wb") as dst:
        dst.write(src.read())
    with open(apk_path, "rb") as src, open(latest_apk, "wb") as dst:
        dst.write(src.read())
    log(f"发布资产已就绪于 dist/: {normalized_apk.name}, {latest_apk.name}, rules.json, update.json")

    # 写入本次更新说明 markdown 备用
    release_md_path = DIST_DIR / f"release-{version_name}.md"
    release_md_path.write_text(manifest["changelog"], encoding="utf-8")

    if args.build_only:
        log("已指定 --build-only，本地制品处理完毕，退出。")
        return

    # 5. 读取认证 Token (绝不硬编码)
    cnb_token = os.environ.get("CNB_TOKEN")
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")

    if not args.dry_run:
        if not cnb_token:
            log("未检测到 CNB_TOKEN 环境变量，无法上传 CNB 仓库！", "WARN")
        if not gh_token:
            log("未检测到 GH_TOKEN 环境变量，将依赖本机已登录的 gh cli 凭据。", "INFO")

    # 6. GitHub Release 发布
    log(">>> [1/3] 开始同步至 GitHub Releases...")
    gh_assets = [normalized_apk, update_json_path, rules_json_path]
    publish_github_release(
        tag=tag_name,
        title=f"fucksh {tag_name}",
        notes_file=release_md_path,
        assets=gh_assets,
        repo=args.github_repo,
        dry_run=args.dry_run
    )

    # 7. CNB Release 发布与更新频道维护
    log(">>> [2/3] 开始同步至 CNB Releases...")
    if cnb_token or args.dry_run:
        cnb = CnbClient(token=cnb_token or "dummy-token", repo=args.cnb_repo, dry_run=args.dry_run)

        # 7.1 发布目标版本 Release (例如 v1.0.0)
        rel_v = cnb.create_or_update_release(
            tag=tag_name,
            title=f"fucksh {tag_name}",
            body=manifest["changelog"],
            make_latest="true"
        )
        if rel_v and "id" in rel_v:
            rid = rel_v["id"]
            cnb.upload_asset(rid, f"fucksh-{tag_name}.apk", normalized_apk, overwrite=True)
            cnb.upload_asset(rid, "update.json", update_json_path, overwrite=True)
            cnb.upload_asset(rid, "rules.json", rules_json_path, overwrite=True)

        # 7.2 发布并维护 stable 稳定频道固定 Release
        log("维护 CNB stable 频道固定发布...")
        rel_stable = cnb.create_or_update_release(
            tag="stable",
            title="fucksh Stable Channel",
            body=f"fucksh 稳定通道当前最新版本: {tag_name}\n\n{manifest['changelog']}",
            make_latest="false"
        )
        if rel_stable and "id" in rel_stable:
            rid = rel_stable["id"]
            cnb.upload_asset(rid, "fucksh-latest.apk", latest_apk, overwrite=True)
            cnb.upload_asset(rid, "update.json", update_json_path, overwrite=True)
            cnb.upload_asset(rid, "rules.json", rules_json_path, overwrite=True)

        # 7.3 发布并维护 update 自动更新固定 Release
        log("维护 CNB update 频道固定发布...")
        rel_update = cnb.create_or_update_release(
            tag="update",
            title="fucksh Update Channel",
            body=f"fucksh 自动更新服务固定元数据（供客户端轮询）\n\n当前承载版本: {tag_name}",
            make_latest="false"
        )
        if rel_update and "id" in rel_update:
            rid = rel_update["id"]
            cnb.upload_asset(rid, "fucksh-latest.apk", latest_apk, overwrite=True)
            cnb.upload_asset(rid, "update.json", update_json_path, overwrite=True)
            cnb.upload_asset(rid, "rules.json", rules_json_path, overwrite=True)
    else:
        log("跳过 CNB 发布：缺少 CNB_TOKEN。", "WARN")

    # 8. Git 镜像推送至 CNB
    log(">>> [3/3] 开始同步 Git 仓库镜像至 CNB...")
    if cnb_token or args.dry_run:
        push_git_mirror_to_cnb(DEFAULT_CNB_GIT_URL, token=cnb_token or "dummy-token", dry_run=args.dry_run)
    else:
        log("跳过 Git 镜像推送：缺少 CNB_TOKEN。", "WARN")

    log("==================================================")
    log("fucksh 双端发布同步任务圆满完成！")
    log(f"- GitHub Release: https://github.com/{args.github_repo}/releases/tag/{tag_name}")
    log(f"- CNB Releases:   https://cnb.cool/{args.cnb_repo}/-/releases")
    log(f"- CNB Update:     https://cnb.cool/{args.cnb_repo}/-/releases/download/update/update.json")
    log(f"- CNB Stable:     https://cnb.cool/{args.cnb_repo}/-/releases/download/stable/fucksh-latest.apk")
    log("==================================================")


if __name__ == "__main__":
    try:
        main()
    except Exception as err:
        log(f"发布过程出现异常终止: {err}", "ERROR")
        sys.exit(1)
