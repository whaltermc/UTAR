extends RefCounted

# Drop-in replacement for the game's proprietary JsonUtils GDExtension
# (jsonutils.dll), which wraps nlohmann::json's diff/patch (RFC 6902).
#
# Used only by res://Store.gd:
#   _json_utils.diff(original_db, current_db) -> {"p": [ops...]}
#   _json_utils.patch(original_db, {"p": [ops...]}) -> patched_db


func diff(a, b) -> Dictionary:
	var ops: Array = []
	_diff(a, b, "", ops)
	return {"p": ops}


func patch(original, diff_obj):
	var ops = diff_obj
	if typeof(diff_obj) == TYPE_DICTIONARY:
		ops = diff_obj.get("p", [])
	if typeof(ops) != TYPE_ARRAY:
		return _deep_dup(original)

	var root = _deep_dup(original)
	for op in ops:
		if typeof(op) != TYPE_DICTIONARY:
			continue
		root = _apply_op(root, op)
	return root


func _apply_op(root, op: Dictionary):
	var op_name: String = op.get("op", "")
	var path: String = op.get("path", "")

	match op_name:
		"add":
			return _set_at(root, path, _deep_dup(op.get("value")), true)
		"replace":
			return _set_at(root, path, _deep_dup(op.get("value")), false)
		"remove":
			return _remove_at(root, path)
		"copy":
			var value = _deep_dup(_get_at(root, op.get("from", "")))
			return _set_at(root, path, value, true)
		"move":
			var value = _deep_dup(_get_at(root, op.get("from", "")))
			root = _remove_at(root, op.get("from", ""))
			return _set_at(root, path, value, true)
	return root


func _diff(a, b, path: String, ops: Array) -> void:
	if typeof(a) != typeof(b):
		ops.append({"op": "replace", "path": path, "value": _deep_dup(b)})
		return

	var t: int = typeof(a)

	if t == TYPE_DICTIONARY:
		for key in a.keys():
			if not b.has(key):
				ops.append({"op": "remove", "path": _ptr(path, str(key))})
		for key in b.keys():
			if not a.has(key):
				ops.append({"op": "add", "path": _ptr(path, str(key)), "value": _deep_dup(b[key])})
		for key in a.keys():
			if b.has(key):
				_diff(a[key], b[key], _ptr(path, str(key)), ops)
		return

	if t == TYPE_ARRAY:
		var common: int = mini(a.size(), b.size())
		for i in range(common):
			_diff(a[i], b[i], _ptr(path, str(i)), ops)
		if b.size() > a.size():
			for i in range(common, b.size()):
				ops.append({"op": "add", "path": _ptr(path, str(i)), "value": _deep_dup(b[i])})
		elif a.size() > common:
			for i in range(a.size() - 1, common - 1, -1):
				ops.append({"op": "remove", "path": _ptr(path, str(i))})
		return

	if a != b:
		ops.append({"op": "replace", "path": path, "value": _deep_dup(b)})


func _set_at(root, path: String, value, is_add: bool):
	if path == "":
		return value

	var tokens := _tokens(path)
	var parent = _walk(root, tokens)
	if parent == null:
		return root

	var last: String = tokens[-1]
	if typeof(parent) == TYPE_DICTIONARY:
		parent[last] = value
	elif typeof(parent) == TYPE_ARRAY:
		var idx: int = parent.size() if last == "-" else int(last)
		if is_add:
			parent.insert(idx, value)
		else:
			parent[idx] = value
	return root


func _remove_at(root, path: String):
	if path == "":
		return null

	var tokens := _tokens(path)
	var parent = _walk(root, tokens)
	if parent == null:
		return root

	var last: String = tokens[-1]
	if typeof(parent) == TYPE_DICTIONARY:
		parent.erase(last)
	elif typeof(parent) == TYPE_ARRAY:
		parent.remove_at(int(last))
	return root


func _get_at(root, path: String):
	if path == "":
		return root

	var tokens := _tokens(path)
	var cur = root
	for token in tokens:
		if typeof(cur) == TYPE_DICTIONARY:
			if not cur.has(token):
				return null
			cur = cur[token]
		elif typeof(cur) == TYPE_ARRAY:
			cur = cur[int(token)]
		else:
			return null
	return cur


func _walk(root, tokens: PackedStringArray):
	var cur = root
	for i in range(tokens.size() - 1):
		var token: String = tokens[i]
		if typeof(cur) == TYPE_DICTIONARY:
			if not cur.has(token):
				return null
			cur = cur[token]
		elif typeof(cur) == TYPE_ARRAY:
			cur = cur[int(token)]
		else:
			return null
	return cur


func _tokens(path: String) -> PackedStringArray:
	var raw := path.split("/", true)
	var out := PackedStringArray()
	for i in range(1, raw.size()):
		out.append(raw[i].replace("~1", "/").replace("~0", "~"))
	return out


func _ptr(base: String, key: String) -> String:
	return base + "/" + key.replace("~", "~0").replace("/", "~1")


func _deep_dup(v):
	if typeof(v) == TYPE_DICTIONARY or typeof(v) == TYPE_ARRAY:
		return v.duplicate(true)
	return v
