import { load, api_fetch, login_data, lang, load_secrets, storage_key, online, alert_error, logout, set_valid_login, storage_data, confirm, username as get_username, username_ref, alert } from "../js/common.js";

let wasm;
let secrets;

let lastSortUpdate = 0;
let sortUpdateSecrets;

const key = document.getElementById("key");
const add = document.getElementById("add");
const add_form = document.getElementById("add_form");
const totp = document.getElementById("totp");
const decryption = document.getElementById("decryption");
const decrypt = document.getElementById("decrypt");
const name = document.getElementById("name");
const secret = document.getElementById("secret");
const tokens = document.getElementById("tokens");
const user_btn = document.getElementById("user_btn");
const logout_el = document.getElementById("logout");
const offline_mode = document.getElementById("offline_mode");
const time_left = document.getElementById("time_left");
const loading = document.getElementById("loading");
const username = document.getElementById("username");
const password = document.getElementById("password");
const disable_offline = document.getElementById("disable_offline");

load(async function (temp_wasm) {
    wasm = temp_wasm;
    const can_decrypt = await try_init();
    if (!can_decrypt) {
        decryption.onsubmit = function () {
            key.disabled = true;
            decryption.disabled = true;
            decrypt.disabled = true;
            decrypt_storage();
            key.disabled = false;
            decryption.disabled = false;
            decrypt.disabled = false;
            return false;
        };
        decryption.hidden = false;
        key.select();
    }
    loading.hidden = true;
});

async function try_init() {
    try {
        await reload_secrets();
        if (storage_data().length == 4) {
            alert_error(lang.empty_storage);
            setTimeout(function () {
                location.href = "../";
            }, 3000);
            return false;
        }
        reload_tokens(true);
        setInterval(reload_tokens, 1000);
        add_form.onsubmit = function () { add_token(); return false; };
        disable_offline.onsubmit = function () {
            if (!username.value.match(/^[0-9a-zA-Z]+$/)) {
                alert_error(lang.username_not_alphanumeric);
                return false;
            }
            username.disabled = true;
            password.disabled = true;
            offline_mode.disabled = true;
            const password_hash = wasm.hash_password(password.value);
            api_fetch(async function (json) {
                if ("token" in json) {
                    localStorage.setItem("username", username.value);
                    localStorage.setItem("token", json.token);
                    set_valid_login(true);
                    await reload_secrets();
                    username_ref.value = get_username();
                    add_form.hidden = !online;
                    user_btn.hidden = !online;
                    disable_offline.hidden = online;
                    totp.hidden = false;
                    decryption.hidden = true;
                } else {
                    alert_error(json.error);
                }
                username.disabled = false;
                password.disabled = false;
                offline_mode.disabled = false;
            }, "user/login", { username: username.value, password: password_hash });
            return false;
        };
        add_form.hidden = !online;
        user_btn.hidden = !online;
        disable_offline.hidden = online;
        totp.hidden = false;
        decryption.hidden = true;
        return true;
    } catch (err) {
        if (err == lang.invalid_key) {
            if (storage_key() != null) {
                alert_error(err);
            }
        } else {
            console.log(err);
        }
        return false;
    }
}

async function decrypt_storage() {
    if (key.value == "") {
        return alert_error(lang.empty_key) == true;
    }
    sessionStorage.setItem("storage_key", wasm.hash_key(key.value))
    if (!await try_init()) {
        return alert_error(lang.decryption_failed) == true;
    }
    return false;
}

async function reload_secrets() {
    secrets = await load_secrets(wasm);
    reload_tokens(true);
}

async function add_token() {
    const secret_value_raw = secret.value.replace(/ /g, '').toUpperCase();
    if (name.value != "" && secret_value_raw != "") {
        if (secrets[name.value] == undefined) {
            disabled(true);
            if (wasm.gen_token(secret_value_raw, BigInt(Date.now())) == "invalid secret") {
                alert_error(lang.invalid_secret);
                return disabled(false);
            }
            const secret_name = wasm.hash_name(name.value);
            const secret_value = wasm.encrypt_hex(secret_value_raw, storage_key());
            const secret_name_encrypted = wasm.encrypt_hex(name.value, storage_key());
            api_fetch(async function (json) {
                if (json.error == false) {
                    reload_secrets();
                    gen_tokens();
                    name.value = "";
                    secret.value = "";
                } else {
                    alert_error(lang.api_error_cs + json.error);
                }
                disabled(false);
            }, "data/update", { secretname: secret_name, secretvalue: secret_value, secretnameencrypted: secret_name_encrypted, ...login_data() });
        } else {
            alert_error(lang.name_exists);
        }
    } else {
        alert_error(lang.name_secret_empty);
    }
}

async function rename_token(name, new_name) {
    if (name != "" && new_name != "") {
        if (secrets[name] != undefined) {
            if (secrets[new_name] == undefined) {
                const secret_name = wasm.hash_name(name);
                const new_secret_name = wasm.hash_name(new_name);
                const secret_name_encrypted = wasm.encrypt_hex(new_name, storage_key());
                api_fetch(async function (json) {
                    if (json.error == false) {
                        let secretNames = Object.keys(secrets);
                        const nameIndex = secretNames.indexOf(name);
                        secretNames[nameIndex] = new_name;
                        secrets[new_name] = secrets[name];
                        delete secrets[name];
                        secrets = JSON.parse(JSON.stringify(secrets, secretNames));
                        gen_tokens();
                        updateSort();
                    } else {
                        alert_error(lang.api_error_cs + json.error);
                    }
                }, "data/rename", { secretname: secret_name, newsecretname: new_secret_name, secretnameencrypted: secret_name_encrypted, ...login_data() });
            } else {
                alert_error(lang.name_exists);
            }
        } else {
            alert_error(lang.name_nonexistent);
        }
    } else {
        alert_error(lang.name_empty);
    }
}

function disabled(disable) {
    name.disabled = disable;
    secret.disabled = disable;
    add.disabled = disable;
}

function remove_token(name) {
    if (name != "") {
        if (secrets[name] != undefined) {
            let secret_name = wasm.hash_name(name);
            api_fetch(async function (json) {
                if (json.error == false) {
                    delete secrets[name];
                    gen_tokens();
                } else {
                    alert_error(lang.api_error_cs + json.error);
                }
            }, "data/delete", { secretname: secret_name, ...login_data() });
        } else {
            alert_error(lang.name_nonexistent)
        }
    } else {
        alert_error(lang.name_empty);
    }
}

function reload_tokens(force = false) {
    const left = 30 - (Date.now() / 1000) % 30;
    const round_left = Math.round(left);
    time_left.setAttribute("aria-valuenow", round_left);
    time_left.style = "width: " + (round_left / 30) * 100 + "%";
    time_left.innerText = round_left;
    if (left > 29 || force) {
        gen_tokens();
    }
}

function updateSort() {
    if (lastSortUpdate > Date.now() - 1000) {
        setTimeout(updateSort, 1000);
    } else if (secrets != sortUpdateSecrets) {
        lastSortUpdate = Date.now();
        sortUpdateSecrets = secrets;
        let sort = "";
        for (const key in secrets) {
            sort += wasm.hash_name(key) + ',';
        }
        api_fetch(async function (json) {
            if (json.error != false) {
                alert_error(lang.api_error_cs + json.error);
            }
        }, "data/update_sort", { secretssort: wasm.encrypt_hex(sort, storage_key()), ...login_data() });
    }
}

function gen_tokens() {
    tokens.innerHTML = "";
    let secretNames = Object.keys(secrets);
    for (const name in secrets) {
        const button_rename = document.createElement("a");
        const button_delete = document.createElement("a");
        const right = document.createElement("div");
        const button_up = document.createElement("button");
        const button_down = document.createElement("button");
        const edit = document.createElement("a");
        const a = document.createElement("a");
        const token = wasm.gen_token(secrets[name], BigInt(Date.now()));
        a.innerHTML = "<div><strong>" + name + "</strong>&nbsp;" + token.substr(0, 3) + " " + token.substr(3) + "</div>";
        a.addEventListener("click", function (ev) {
            if (ev.target != edit && ev.target != button_up && ev.target != button_down) {
                const el = document.createElement("textarea");
                el.value = token;
                document.body.appendChild(el);
                el.select();
                document.execCommand('copy');
                document.body.removeChild(el);
            }
        });
        a.classList.add("list-group-item");
        a.classList.add("list-group-item-action");
        a.classList.add("d-flex");
        a.classList.add("justify-content-between");
        a.classList.add("align-items-center");
        a.classList.add("token");
        a.href = "#";
        if (online) {
            if (secretNames.indexOf(name) == 0)
                button_up.disabled = true;
            button_up.innerHTML = "&#11014;&#65039";
            button_up.classList.add("btn");
            button_up.classList.add("btn-default");
            button_up.classList.add("button-updown");
            button_up.type = "button";
            button_up.addEventListener("click", function () {
                if (secretNames.indexOf(name) == 0)
                    return
                const nameIndex = secretNames.indexOf(name);
                const before = secretNames[nameIndex - 1];
                secretNames[nameIndex - 1] = secretNames[nameIndex];
                secretNames[nameIndex] = before;
                secrets = JSON.parse(JSON.stringify(secrets, secretNames));
                gen_tokens();
                updateSort();
            });

            if (secretNames.indexOf(name) == secretNames.length - 1)
                button_down.disabled = true;
            button_down.innerHTML = "&#11015;&#65039";
            button_down.classList.add("btn");
            button_down.classList.add("btn-default");
            button_down.classList.add("button-updown");
            button_down.type = "button";
            button_down.addEventListener("click", function () {
                if (secretNames.indexOf(name) == secretNames.length - 1)
                    return
                const nameIndex = secretNames.indexOf(name);
                const after = secretNames[nameIndex + 1];
                secretNames[nameIndex + 1] = secretNames[nameIndex];
                secretNames[nameIndex] = after;
                secrets = JSON.parse(JSON.stringify(secrets, secretNames));
                gen_tokens();
                updateSort();
            });

            edit.innerText = lang.edit;
            edit.addEventListener("click", function () {
                let action;
                confirm("", function () {
                    const input = document.getElementById("temp_input_edit_secret");
                    if (action == "rename") {
                        confirm(lang.sure_to_rename_to.replace("$name", name).replace("$new_name", input.value), function () {
                            rename_token(name, input.value);
                        });
                    } else if (action == "delete") {
                        if (input.value == name) {
                            confirm(lang.delete_secret_qm.replace("$name", name), function () {
                                const modal_btn = document.getElementById("modal_btn");
                                modal_btn.disabled = true;
                                confirm(lang.sure_to_delete.replace("$name", name), function () {
                                    remove_token(name);
                                }, "<div class=\"progress ten-top-margin\"><div id=\"delete_timeout\" class=\"progress-bar\" role=\"progressbar\" aria-valuenow=\"5\" aria-valuemin=\"0\" aria-valuemax=\"5\" style=\"width:0%\"></div></div>");
                                const delete_timeout = document.getElementById("delete_timeout");
                                let i = 0;
                                const progressInterval = setInterval(() => {
                                    delete_timeout.style = "width:" + (2.1 * i++) + "%;";
                                    if (i == 50) {
                                        clearInterval(progressInterval);
                                        modal_btn.disabled = false;
                                    }
                                }, 100);
                            });
                        } else {
                            alert_error(lang.repeat_name_incorrect);
                        }
                    } else {
                        alert_error(lang.no_action_selected);
                    }
                }, "<button type=\"button\" class=\"btn btn-warning\" id=\"rename_secret\">" + lang.rename + "</button>" +
                "<button type=\"button\" class=\"btn btn-danger\" id=\"delete_secret\">" + lang.delete + "</button>" +
                "<input autocomplete=\"off\" id=\"temp_input_edit_secret\" class=\"form-control ten-top-margin\" type=\"text\" hidden>");
                const input = document.getElementById("temp_input_edit_secret");
                const rename_secret = document.getElementById("rename_secret");
                const delete_secret = document.getElementById("delete_secret");
                rename_secret.addEventListener("click", function () {
                    rename_secret.disabled = true;
                    delete_secret.disabled = false;
                    input.hidden = false
                    input.placeholder = lang.new_name_for.replace("$name", name);
                    input.value = "";
                    action = "rename";
                });
                delete_secret.addEventListener("click", function () {
                    delete_secret.disabled = true;
                    rename_secret.disabled = false;
                    input.hidden = false
                    input.placeholder = lang.repeat_secret_name.replace("$name", name);
                    input.value = "";
                    action = "delete";
                });
            });
            edit.classList.add("badge");
            edit.classList.add("bg-info");
            edit.classList.add("rounded-pill");
            edit.classList.add("rename-button");
            edit.href = "#";

            right.appendChild(button_up);
            right.appendChild(button_down);
            right.appendChild(edit);
            a.appendChild(right);
        }
        tokens.appendChild(a);
    }
}

logout(logout_el);
