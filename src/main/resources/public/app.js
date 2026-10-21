// What the pages do in the browser. It's all here rather than in the pages, so the Content-Security-Policy can forbid
// scripts in pages: even a value that slipped past the templates' escaping couldn't run.
'use strict';

document.addEventListener('click', (event) => {
    const element = event.target.closest('.navbar-burger, .notification > .delete, [data-open], [data-close], [data-add], [data-remove]');
    if (element === null) {
        return;
    }
    if (element.matches('.navbar-burger')) {
        const isOpen = element.classList.toggle('is-active');
        document.getElementById(element.getAttribute('aria-controls')).classList.toggle('is-active', isOpen);
        element.setAttribute('aria-expanded', String(isOpen));
    } else if (element.matches('.notification > .delete')) {
        element.parentElement.remove();
    } else if (element.matches('[data-open]')) {
        document.getElementById(element.dataset.open).showModal();
    } else if (element.matches('[data-close]')) {
        element.closest('dialog').close();
    } else if (element.matches('[data-add]')) {
        addRow(document.getElementById(element.dataset.add));
    } else if (element.matches('[data-remove]')) {
        const row = element.closest('.repeat-row');
        if (row.parentElement.children.length > 1) {
            row.remove();
        }
    }
});

// Clicking outside a dialog closes it, like pressing Escape does
document.addEventListener('click', (event) => {
    if (event.target instanceof HTMLDialogElement) {
        event.target.close();
    }
});

/** Another row like the list's first, empty, for e.g. one more command in an action. */
function addRow(list) {
    const row = list.firstElementChild.cloneNode(true);
    row.querySelectorAll('input').forEach((input) => { input.value = ''; });
    row.querySelectorAll('select').forEach((select) => { select.selectedIndex = 0; });
    row.querySelectorAll('[data-operation]').forEach(showParameters);
    list.append(row);
    row.querySelector('input, select')?.focus();
}

// A command's parameters are named by its operation, e.g. Area and Scene number, and hidden when it doesn't use them
document.addEventListener('change', (event) => {
    if (event.target.matches('[data-operation]')) {
        showParameters(event.target);
    }
});

function showParameters(select) {
    const names = select.selectedOptions[0].dataset.parameters.split('|');
    select.closest('.repeat-row').querySelectorAll('[data-parameter]').forEach((field) => {
        const name = names[Number(field.dataset.parameter) - 1] ?? '';
        const input = field.querySelector('input');
        field.hidden = name === '';
        field.querySelector('label').textContent = name;
        input.setAttribute('aria-label', name);
        if (name === '') {
            input.value = '';
        }
    });
}

// Live parts of a page, e.g. the bus graph on Status: the server sends them again every few seconds while the page is
// open. EventSource reconnects by itself, e.g. after the server restarts; the Live tag shows whether it's connected.
document.querySelectorAll('[data-live]').forEach((element) => {
    const events = new EventSource(element.dataset.live);
    events.addEventListener('update', (event) => { element.innerHTML = event.data; });
    events.addEventListener('open', () => document.body.classList.add('is-live'));
    events.addEventListener('error', () => document.body.classList.remove('is-live'));
});
