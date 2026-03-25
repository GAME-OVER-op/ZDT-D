-- ============================================================
--  zapret-sni.lua (AUTO + DEBUG)
--  Lua 5.1 / LuaJIT compatible
--  CRITICAL: does NOT change TCP payload length (in-place only)
-- ============================================================

-- --------------------------
-- Config
-- --------------------------
local CONFIG = {
    max_attempts_per_server = 3,
    success_cache_time      = 3600,
    failure_penalty_time    = 300,
    check_timeout           = 5,
    fallback_to_original    = true,

    -- rawsend-safe mode:
    require_same_length     = true,
    use_reasm_data          = false, -- MUST be false to avoid EMSGSIZE in many inline engines

    -- Debug
    debug                  = true,
    log_path               = "/data/adb/modules/ZDT-D/strategic/log/sni-debug.log",
    log_throttle_sec       = 1,      -- throttle noisy logs
}

-- Engine-dependent constants (safe fallbacks)
local VERDICT_MODIFY_SAFE = rawget(_G, "VERDICT_MODIFY") or 1
local TH_RST_SAFE         = rawget(_G, "TH_RST") or 0x04

-- --------------------------
-- Globals / caches
-- --------------------------
local sni_list = {}
local sni_success_cache = {}   -- server_key -> {sni, timestamp, count, group, len}
local sni_failure_cache = {}   -- server_key:sni -> timestamp
local sni_attempt_history = {} -- server_key -> attempts

-- indexes: group -> len -> array of sni_info
local sni_by_group_len = {}
local sni_by_len = {}

-- throttle state
local _last_noisy_log_ts = 0
local _loaded_logged = false

-- --------------------------
-- Logging
-- --------------------------
local function _append_file(path, line)
    local ok, f = pcall(io.open, path, "a")
    if ok and f then
        f:write(line, "\n")
        f:close()
        return true
    end
    return false
end

local function dlog(msg, noisy)
    if not CONFIG.debug then return end
    if noisy then
        local now = os.time()
        if now == _last_noisy_log_ts then return end
        _last_noisy_log_ts = now
    end

    local line = os.date("%F %T") .. " [sni] " .. tostring(msg)

    if type(DLOG) == "function" then
        DLOG(line)
    end

    _append_file(CONFIG.log_path, line)
end

-- try to ensure log dir exists (best-effort)
do
    if type(os.execute) == "function" then
        pcall(os.execute, "mkdir -p /data/adb/modules/ZDT-D/strategic/log >/dev/null 2>&1")
    end
end

dlog("zapret-sni.lua LOADED (LuaJIT/Lua 5.1)", false)
_loaded_logged = true

-- --------------------------
-- Helpers
-- --------------------------
local function trim(s)
    s = s or ""
    return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

local function round_int(x)
    x = tonumber(x) or 0
    if x < 0 then return 0 end
    return math.floor(x + 0.5)
end

local function safe_get_lua_state(desync)
    if not desync then return nil end
    if not desync.track then desync.track = {} end
    if not desync.track.lua_state then desync.track.lua_state = {} end
    return desync.track.lua_state
end

-- Bits: LuaJIT(bit), Lua 5.2+(bit32), or globals
local function _band(a, b)
    if bit and bit.band then return bit.band(a, b) end
    if bit32 and bit32.band then return bit32.band(a, b) end
    if _G.bitand then return _G.bitand(a, b) end
    error("No bitwise AND function available")
end

local function _bxor(a, b)
    if bit and bit.bxor then return bit.bxor(a, b) end
    if bit32 and bit32.bxor then return bit32.bxor(a, b) end
    if _G.bitxor then return _G.bitxor(a, b) end
    error("No bitwise XOR function available")
end

-- seed random once
do
    local extra = 0
    local ok, addr = pcall(function()
        return tostring({}):match("0x(%x+)")
    end)
    if ok and addr then extra = tonumber(addr, 16) or 0 end
    math.randomseed(os.time() + extra)
    math.random(); math.random(); math.random()
end

local function simple_hash(data)
    local hash = 0
    local n = math.min(#data, 100)
    for i = 1, n do
        hash = _bxor(hash * 31, string.byte(data, i))
    end
    return hash
end

-- --------------------------
-- Binary helpers (big-endian)
-- --------------------------
local function u8(s, i)
    local b = string.byte(s, i)
    if not b then return nil end
    return b
end

local function u16(s, i)
    local b1, b2 = string.byte(s, i, i + 1)
    if not b2 then return nil end
    return b1 * 256 + b2
end

local function u24(s, i)
    local b1, b2, b3 = string.byte(s, i, i + 2)
    if not b3 then return nil end
    return b1 * 65536 + b2 * 256 + b3
end

-- --------------------------
-- Grouping by zone
--  >=4 labels => last 3 labels, else last 2 labels
-- --------------------------
local function split_labels(host)
    local labels = {}
    for part in tostring(host or ""):gmatch("([^.]+)") do
        labels[#labels + 1] = part
    end
    return labels
end

local function group_key_from_host(host)
    local labels = split_labels(host)
    local n = #labels
    if n >= 4 then
        return labels[n-2] .. "." .. labels[n-1] .. "." .. labels[n]
    elseif n >= 2 then
        return labels[n-1] .. "." .. labels[n]
    end
    return tostring(host or "")
end

-- --------------------------
-- Load SNI list
-- --------------------------
local function add_to_index(sni_info)
    local len = sni_info.len
    local g = sni_info.group

    if not sni_by_len[len] then sni_by_len[len] = {} end
    sni_by_len[len][#sni_by_len[len] + 1] = sni_info

    if not sni_by_group_len[g] then sni_by_group_len[g] = {} end
    if not sni_by_group_len[g][len] then sni_by_group_len[g][len] = {} end
    sni_by_group_len[g][len][#sni_by_group_len[g][len] + 1] = sni_info
end

function load_sni_list(filename)
    local f = io.open(filename, "r")
    if not f then
        error("Cannot open SNI list file: " .. tostring(filename))
    end

    sni_by_group_len = {}
    sni_by_len = {}

    local list = {}
    local count = 0
    for line in f:lines() do
        line = trim(line or "")
        if line ~= "" and not line:match("^#") then
            -- supports: sni,weight,tags...
            local sni, weight_str, tags_str = line:match("^([^,]+),?(%d*),?(.*)$")
            sni = trim(sni or "")
            if sni ~= "" then
                local weight = tonumber(weight_str) or 1
                if weight < 1 then weight = 1 end

                local tags = {}
                tags_str = trim(tags_str or "")
                if tags_str ~= "" then
                    for tag in tags_str:gmatch("[^,]+") do
                        tag = trim(tag)
                        if tag ~= "" then tags[tag] = true end
                    end
                end

                local info = {
                    sni = sni,
                    len = #sni,
                    group = group_key_from_host(sni),
                    weight = weight,
                    tags = tags,

                    attempts = 0,
                    successes = 0,
                    failures = 0,
                    last_used = 0,
                    last_success = 0,
                    last_failure = 0,
                    health_score = 100,
                }

                list[#list + 1] = info
                add_to_index(info)
                count = count + 1
            end
        end
    end
    f:close()

    dlog("Loaded " .. tostring(count) .. " SNIs from " .. tostring(filename), false)
    return list
end

-- --------------------------
-- server key
-- --------------------------
local function get_server_key(desync)
    local port = 0
    if desync and desync.dis then
        if desync.dis.tcp and desync.dis.tcp.th_dport then
            port = desync.dis.tcp.th_dport
        elseif desync.dis.udp and desync.dis.udp.uh_dport then
            port = desync.dis.udp.uh_dport
        end
    end

    if desync and desync.dis and desync.dis.ipv and desync.dis.ipv.ip_dst then
        return string.format("%s:%d", tostring(desync.dis.ipv.ip_dst), port)
    elseif desync and desync.dis and desync.dis.ip6 and desync.dis.ip6.ip6_dst then
        return string.format("[%s]:%d", tostring(desync.dis.ip6.ip6_dst), port)
    end

    return "unknown:" .. tostring(port)
end

-- --------------------------
-- TLS ClientHello parser (only locate SNI in current segment)
-- returns: { sni = { host, host_off, host_len } } or nil
-- --------------------------
local function parse_tls_client_hello(tls_data)
    if type(tls_data) ~= "string" or #tls_data < 6 then return nil end

    local first = u8(tls_data, 1)
    if not first then return nil end

    local has_record = (first == 20 or first == 21 or first == 22 or first == 23)
    local hs_off = has_record and 6 or 1
    if #tls_data < hs_off + 3 then return nil end

    local hs_type = u8(tls_data, hs_off)
    if hs_type ~= 1 then return nil end -- not ClientHello

    local hs_len = u24(tls_data, hs_off + 1)
    if not hs_len then return nil end

    local body_off = hs_off + 4
    local body_end = body_off + hs_len - 1
    if body_end > #tls_data then
        return nil -- fragmented/incomplete in this segment
    end

    local cur = body_off

    -- legacy_version(2) + random(32)
    cur = cur + 2 + 32
    if cur > body_end then return nil end

    -- session_id
    local sid_len = u8(tls_data, cur); if not sid_len then return nil end
    cur = cur + 1 + sid_len
    if cur > body_end then return nil end

    -- cipher_suites
    local cs_len = u16(tls_data, cur); if not cs_len then return nil end
    cur = cur + 2 + cs_len
    if cur > body_end then return nil end

    -- compression_methods
    local cm_len = u8(tls_data, cur); if not cm_len then return nil end
    cur = cur + 1 + cm_len
    if cur > body_end + 1 then return nil end

    -- extensions
    if cur > body_end - 1 then return { sni = nil } end

    local ext_total_len = u16(tls_data, cur)
    if not ext_total_len then return nil end
    local ext_off = cur + 2
    local ext_end = ext_off + ext_total_len - 1
    if ext_end > body_end then return nil end

    local i = ext_off
    while i <= ext_end do
        if i + 3 > ext_end then break end
        local etype = u16(tls_data, i)
        local elen  = u16(tls_data, i + 2)
        if not etype or not elen then break end

        local edata_off = i + 4
        local edata_end = edata_off + elen - 1
        if edata_end > ext_end then break end

        if etype == 0x0000 and elen >= 5 then
            local list_len = u16(tls_data, edata_off)
            if list_len and list_len >= 3 then
                local name_type = u8(tls_data, edata_off + 2)
                if name_type == 0 then
                    local name_len = u16(tls_data, edata_off + 3)
                    local name_off = edata_off + 5
                    if name_len and (name_off + name_len - 1) <= edata_end then
                        local host = tls_data:sub(name_off, name_off + name_len - 1)
                        return {
                            sni = { host = host, host_off = name_off, host_len = name_len }
                        }
                    end
                end
            end
        end

        i = i + 4 + elen
    end

    return { sni = nil }
end

-- --------------------------
-- Fixed-size in-place replace
-- --------------------------
local function replace_sni_fixed(tls_data, old_off, old_len, new_sni)
    if old_off <= 0 or old_len <= 0 then return nil end
    if type(new_sni) ~= "string" then return nil end
    if #new_sni ~= old_len then return nil end
    return tls_data:sub(1, old_off - 1) .. new_sni .. tls_data:sub(old_off + old_len)
end

-- --------------------------
-- Select best SNI: prefer same group+len, else global len
-- --------------------------
local function select_best_sni(server_key, group, required_len)
    local now = os.time()

    -- cached success first
    local cached = sni_success_cache[server_key]
    if cached and cached.sni and cached.timestamp then
        if (now - cached.timestamp) < CONFIG.success_cache_time then
            if cached.len == required_len and (cached.group == group) then
                return cached.sni
            end
        else
            sni_success_cache[server_key] = nil
        end
    end

    local candidates = nil
    if group and required_len and sni_by_group_len[group] and sni_by_group_len[group][required_len] then
        candidates = sni_by_group_len[group][required_len]
    elseif required_len and sni_by_len[required_len] then
        candidates = sni_by_len[required_len]
    end
    if not candidates or #candidates == 0 then return nil end

    local total = 0
    local pool = {}

    for _, info in ipairs(candidates) do
        local health = tonumber(info.health_score) or 0
        if health > 0 then
            local failure_key = server_key .. ":" .. info.sni
            local fail_ts = sni_failure_cache[failure_key]
            if fail_ts then
                if (now - fail_ts) < CONFIG.failure_penalty_time then
                    -- penalized
                else
                    sni_failure_cache[failure_key] = nil
                    fail_ts = nil
                end
            end

            if not fail_ts then
                local weight = tonumber(info.weight) or 1
                if weight < 1 then weight = 1 end
                local score = health * weight
                if score > 0 then
                    total = total + score
                    pool[#pool + 1] = { sni = info.sni, score = score, ref = info }
                end
            end
        end
    end

    if #pool == 0 or total <= 0 then return nil end

    local r = math.random() * total
    local acc = 0
    for _, item in ipairs(pool) do
        acc = acc + item.score
        if r <= acc then
            item.ref.attempts = (item.ref.attempts or 0) + 1
            item.ref.last_used = now
            return item.sni
        end
    end

    pool[1].ref.attempts = (pool[1].ref.attempts or 0) + 1
    pool[1].ref.last_used = now
    return pool[1].sni
end

-- --------------------------
-- Stats update
-- --------------------------
local function record_sni_success(server_key, sni_used, group, len)
    local now = os.time()

    for _, info in ipairs(sni_list) do
        if info.sni == sni_used then
            info.successes = (info.successes or 0) + 1
            info.last_success = now
            info.health_score = math.min(100, (info.health_score or 0) + 10)

            local total_attempts = (info.successes or 0) + (info.failures or 0)
            if total_attempts > 100 then
                info.successes = round_int((info.successes or 0) * 0.9)
                info.failures  = round_int((info.failures or 0) * 0.9)
            end
            break
        end
    end

    local prev = sni_success_cache[server_key]
    sni_success_cache[server_key] = {
        sni = sni_used,
        group = group,
        len = len,
        timestamp = now,
        count = (prev and prev.count or 0) + 1
    }

    sni_attempt_history[server_key] = 0
    dlog("SUCCESS server=" .. server_key .. " sni=" .. sni_used, true)
end

local function record_sni_failure(server_key, sni_used)
    local now = os.time()
    local failure_key = server_key .. ":" .. sni_used

    for _, info in ipairs(sni_list) do
        if info.sni == sni_used then
            info.failures = (info.failures or 0) + 1
            info.last_failure = now
            info.health_score = math.max(0, (info.health_score or 0) - 30)
            break
        end
    end

    sni_failure_cache[failure_key] = now
    dlog("FAIL server=" .. server_key .. " sni=" .. sni_used, true)
end

-- --------------------------
-- Main hook
-- --------------------------
function tls_sni_intelligent(ctx, desync)
    if not desync or not desync.dis or not desync.dis.tcp then
        if type(instance_cutoff_shim) == "function" then
            instance_cutoff_shim(ctx, desync)
        end
        return
    end

    if type(direction_cutoff_opposite) == "function" then
        direction_cutoff_opposite(ctx, desync)
    end

    -- init list once
    if #sni_list == 0 and desync.arg and desync.arg.file then
        sni_list = load_sni_list(desync.arg.file)
        if #sni_list == 0 then
            error("tls_sni_intelligent: No SNIs loaded from file")
        end
    end

    -- outgoing?
    local is_outgoing = true
    if type(direction_check) == "function" then
        is_outgoing = direction_check(desync) and true or false
    elseif desync.outgoing ~= nil then
        is_outgoing = desync.outgoing and true or false
    end

    if desync.l7payload ~= "tls_client_hello" or not is_outgoing then
        return
    end

    local server_key = get_server_key(desync)

    -- CRITICAL: use only payload of current segment (rawsend-safe)
    local tls_data = (desync.dis and desync.dis.payload) or ""
    if type(tls_data) ~= "string" or #tls_data == 0 then
        dlog("clienthello: empty payload server=" .. server_key, true)
        return
    end

    -- debug hook ping (throttled)
    dlog("HOOK clienthello server=" .. server_key ..
         " plen=" .. tostring(#tls_data) ..
         " rlen=" .. tostring(desync.reasm_data and #desync.reasm_data or -1), true)

    local hello = parse_tls_client_hello(tls_data)
    if not hello or not hello.sni or not hello.sni.host then
        dlog("clienthello: parse fail/fragmented server=" .. server_key .. " plen=" .. tostring(#tls_data), true)
        return
    end

    local original_sni = hello.sni.host
    local required_len = hello.sni.host_len
    local group = group_key_from_host(original_sni)

    dlog("clienthello: sni=" .. tostring(original_sni) ..
         " len=" .. tostring(required_len) ..
         " group=" .. tostring(group), true)

    -- dynamic pick
    local picked = select_best_sni(server_key, group, required_len)
    if not picked then
        dlog("no replacement found (need len=" .. tostring(required_len) .. ", group=" .. tostring(group) .. ")", true)
        return
    end

    -- enforce fixed length
    if CONFIG.require_same_length and #picked ~= required_len then
        dlog("picked length mismatch picked=" .. tostring(picked) ..
             " plen=" .. tostring(#picked) .. " need=" .. tostring(required_len), true)
        return
    end

    -- attempts per server
    local attempts = (sni_attempt_history[server_key] or 0) + 1
    sni_attempt_history[server_key] = attempts
    if attempts > CONFIG.max_attempts_per_server then
        local cached = sni_success_cache[server_key]
        if cached and cached.sni and cached.len == required_len then
            picked = cached.sni
        else
            picked = original_sni
        end
    end

    if picked == original_sni then
        dlog("picked == original, skip modify", true)
        return
    end

    local modified = replace_sni_fixed(tls_data, hello.sni.host_off, hello.sni.host_len, picked)
    if not modified then
        dlog("replace failed (offset/len invalid?)", true)
        return
    end

    desync.dis.payload = modified

    -- tracking
    local lua_state = safe_get_lua_state(desync)
    lua_state.sni_tracking = lua_state.sni_tracking or {}
    lua_state.sni_tracking[server_key] = {
        sni_used = picked,
        group = group,
        len = required_len,
        timestamp = os.time(),
        original_sni = original_sni,
        packet_hash = simple_hash(tls_data)
    }

    dlog("MODIFY " .. tostring(original_sni) .. " -> " .. tostring(picked) ..
         " (len=" .. tostring(required_len) .. " group=" .. tostring(group) .. ")", false)

    return VERDICT_MODIFY_SAFE
end

-- --------------------------
-- Verify incoming result (heuristic)
-- --------------------------
function tls_sni_verify_result(ctx, desync)
    if not desync or not desync.dis or not desync.dis.tcp then
        return
    end

    -- incoming?
    local is_outgoing = nil
    if desync.outgoing ~= nil then
        is_outgoing = desync.outgoing and true or false
    elseif type(direction_check) == "function" then
        is_outgoing = direction_check(desync) and true or false
    end
    if is_outgoing == true then return end

    local server_key = get_server_key(desync)
    local lua_state = safe_get_lua_state(desync)
    if not lua_state or not lua_state.sni_tracking then return end

    local info = lua_state.sni_tracking[server_key]
    if not info then return end

    local now = os.time()
    if (now - (info.timestamp or 0)) > CONFIG.check_timeout then
        dlog("verify timeout server=" .. server_key .. " sni=" .. tostring(info.sni_used), true)
        lua_state.sni_tracking[server_key] = nil
        return
    end

    local is_success = false

    if desync.l7payload == "tls_server_hello" then
        is_success = true
    elseif desync.l7payload == "tls_application_data" and desync.dis.payload and #desync.dis.payload > 0 then
        is_success = true
    else
        local flags = desync.dis.tcp.th_flags
        if flags then
            local ok, v = pcall(_band, flags, TH_RST_SAFE)
            if ok and v ~= 0 then
                record_sni_failure(server_key, info.sni_used)
                lua_state.sni_tracking[server_key] = nil
                return
            end
        end
    end

    if is_success then
        record_sni_success(server_key, info.sni_used, info.group, info.len)
        lua_state.sni_tracking[server_key] = nil
    end
end

-- --------------------------
-- Optional: stats helpers
-- --------------------------
function table_count(t)
    local c = 0
    for _ in pairs(t or {}) do c = c + 1 end
    return c
end

function print_sni_statistics()
    dlog("=== SNI Statistics ===", false)
    dlog("Total SNIs: " .. tostring(#sni_list), false)
    dlog("Success cache entries: " .. tostring(table_count(sni_success_cache)), false)
    dlog("Failure cache entries: " .. tostring(table_count(sni_failure_cache)), false)

    table.sort(sni_list, function(a, b)
        return (a.health_score or 0) > (b.health_score or 0)
    end)

    for i = 1, math.min(10, #sni_list) do
        local s = sni_list[i]
        dlog(string.format("%2d. %-30s len:%3d health:%3d%% succ:%4d fail:%4d",
            i, tostring(s.sni), tonumber(s.len) or 0,
            tonumber(s.health_score) or 0,
            tonumber(s.successes) or 0,
            tonumber(s.failures) or 0
        ), false)
    end
    dlog("======================", false)
end
