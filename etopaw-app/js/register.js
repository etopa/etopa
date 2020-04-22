import { load, api_fetch } from "./common.js";

load(async function (wasm) {
    document.getElementById("form").onsubmit = function () {
        const username = document.getElementById("username").value;
        const password = wasm.hash_password(document.getElementById("password").value, username);
        api_fetch(async function (json) {
            if ("token" in json) {
                sessionStorage.setItem("username", username);
                sessionStorage.setItem("token", json.token);
                document.getElementById("result").innerText = "Registration successful";
                location.href = "./index.html";
            } else {
                document.getElementById("result").innerText = json.error;
            }
        }, "user/register", { username, password });
        return false;
    };
}, false);