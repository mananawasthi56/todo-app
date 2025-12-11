/* Full TaskMaster Pro Script */

const STORAGE = "taskmaster_v1";
let tasks = [];
let projects = ["Inbox", "Work", "Personal"];
let currentProject = "Inbox";
let editId = null;
let chart = null;

/* ---------------- INIT ---------------- */
window.onload = () => {
    bindUI();
    loadLocal();
    renderProjects();
    render();
    analytics();
};

/* ---------------- UI EVENTS ---------------- */
function bindUI() {
    document.getElementById("addBtn").onclick = addTask;
    document.getElementById("clearBtn").onclick = clearAll;
    document.getElementById("exportBtn").onclick = exportJSON;
    document.getElementById("importBtn").onclick = () => document.getElementById("importFile").click();
    document.getElementById("importFile").onchange = importJSON;
    document.getElementById("addProjectBtn").onclick = addProject;
    document.getElementById("themeToggle").onclick = toggleTheme;

    document.getElementById("saveEdit").onclick = saveEdit;
    document.getElementById("cancelEdit").onclick = closeEdit;

    // Close modal when clicking outside
    document.getElementById("editModal").addEventListener("click", (e) => {
        if (e.target.id === "editModal") closeEdit();
    });

    // ESC to close
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") closeEdit();
    });
}

/* ---------------- STORAGE ---------------- */
function saveLocal() {
    localStorage.setItem(STORAGE, JSON.stringify({ tasks, projects }));
}

function loadLocal() {
    const saved = JSON.parse(localStorage.getItem(STORAGE) || "{}");
    tasks = saved.tasks || [];
    projects = saved.projects || projects;
}

/* ---------------- PROJECTS ---------------- */
function renderProjects() {
    const box = document.getElementById("projectList");
    box.innerHTML = "";

    projects.forEach((p) => {
        const item = document.createElement("div");
        item.className = "project-item" + (p === currentProject ? " active" : "");
        item.textContent = p;
        item.onclick = () => {
            currentProject = p;
            document.getElementById("projectTitle").innerText = p;
            render();
            analytics();
            renderProjects();
        };
        box.appendChild(item);
    });
}

function addProject() {
    const name = prompt("Project name:");
    if (!name) return;

    if (projects.includes(name)) {
        alert("Project already exists");
        return;
    }

    projects.push(name);
    saveLocal();
    renderProjects();
}

/* ---------------- TASK CRUD ---------------- */
function addTask() {
    const title = document.getElementById("taskInput").value.trim();
    if (!title) return;

    const task = {
        id: crypto.randomUUID(),
        title,
        project: currentProject,
        priority: Number(document.getElementById("priority").value),
        due: document.getElementById("due").value || null,
        done: false
    };

    tasks.push(task);
    saveLocal();
    render();
    analytics();

    document.getElementById("taskInput").value = "";
}

function openEdit(task) {
    if (!task) return;

    editId = task.id;

    document.getElementById("editTitle").value = task.title;
    document.getElementById("editPriority").value = task.priority;
    document.getElementById("editDue").value = task.due || "";

    document.getElementById("editModal").classList.remove("hidden");
}

function closeEdit() {
    editId = null;
    document.getElementById("editModal").classList.add("hidden");
}

function saveEdit() {
    const t = tasks.find((x) => x.id === editId);
    if (!t) return;

    t.title = document.getElementById("editTitle").value.trim();
    t.priority = Number(document.getElementById("editPriority").value);
    t.due = document.getElementById("editDue").value;

    saveLocal();
    closeEdit();
    render();
    analytics();
}

function toggleDone(id) {
    const t = tasks.find((x) => x.id === id);
    if (!t) return;

    t.done = !t.done;
    saveLocal();
    render();
    analytics();
}

function deleteTask(id) {
    tasks = tasks.filter((t) => t.id !== id);
    saveLocal();
    render();
    analytics();
}

function clearAll() {
    if (!confirm("Clear all tasks?")) return;
    tasks = tasks.filter((t) => t.project !== currentProject);
    saveLocal();
    render();
    analytics();
}

/* ---------------- RENDER LIST ---------------- */
function render() {
    const list = document.getElementById("taskList");
    list.innerHTML = "";

    const filtered = tasks.filter((t) => t.project === currentProject);

    document.getElementById("taskStats").innerText =
        `${filtered.length} tasks`;

    filtered.forEach((t) => {
        const row = document.createElement("div");
        row.className = "task";

        const left = document.createElement("div");
        left.style.display = "flex";
        left.style.gap = "10px";
        left.style.alignItems = "center";

        const box = document.createElement("div");
        box.className = "checkbox" + (t.done ? " checked" : "");
        box.innerHTML = t.done ? "✔" : "";
        box.onclick = () => toggleDone(t.id);

        const title = document.createElement("span");
        title.innerText = t.title;

        left.appendChild(box);
        left.appendChild(title);

        const right = document.createElement("div");
        right.style.display = "flex";
        right.style.gap = "8px";

        const edit = document.createElement("button");
        edit.className = "btn small";
        edit.innerText = "Edit";
        edit.onclick = () => openEdit(t);

        const del = document.createElement("button");
        del.className = "btn danger small";
        del.innerText = "Delete";
        del.onclick = () => deleteTask(t.id);

        right.appendChild(edit);
        right.appendChild(del);

        row.appendChild(left);
        row.appendChild(right);

        list.appendChild(row);
    });
}

/* ---------------- ANALYTICS ---------------- */
function analytics() {
    const total = tasks.length;
    const done = tasks.filter(t => t.done).length;
    const pending = total - done;

    document.getElementById("ana_total").innerText = total;
    document.getElementById("ana_done").innerText = done;
    document.getElementById("ana_pending").innerText = pending;
    document.getElementById("ana_rate").innerText =
        total ? Math.round((done / total) * 100) + "%" : "0%";

    const ctx = document.getElementById("anaChart");

    if (!chart) {
        chart = new Chart(ctx, {
            type: "pie",
            data: {
                labels: ["Done", "Pending"],
                datasets: [{
                    data: [done, pending],
                    backgroundColor: ["#1a73e8", "#d93025"],
                    borderColor: "#fff",
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false
            }
        });
    } else {
        chart.data.datasets[0].data = [done, pending];
        chart.update();
    }
}

/* ---------------- THEME ---------------- */
function toggleTheme() {
    document.body.classList.toggle("dark");
}

/* ---------------- EXPORT/IMPORT ---------------- */
function exportJSON() {
    const data = JSON.stringify({ tasks, projects }, null, 2);
    const blob = new Blob([data], { type: "application/json" });

    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "tasks.json";
    a.click();
}

function importJSON(e) {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = () => {
        try {
            const data = JSON.parse(reader.result);
            tasks = data.tasks || [];
            projects = data.projects || projects;
            saveLocal();
            render();
            renderProjects();
            analytics();
        } catch {
            alert("Invalid JSON file");
        }
    };
    reader.readAsText(file);
}
