"""Lazily expose tools without importing optional RAG dependencies eagerly."""

from importlib import import_module


_EXPORTS = {
    "customer_lookup": "agent.tools.customer_service",
    "logistics_lookup": "agent.tools.customer_service",
    "order_lookup": "agent.tools.customer_service",
    "ticket_create": "agent.tools.customer_service",
    "ticket_lookup": "agent.tools.ticket_service",
    "ticket_urge": "agent.tools.ticket_service",
    "policy_search": "agent.tools.policy_search",
    "web_search": "agent.tools.web_search",
}

__all__ = list(_EXPORTS)


def __getattr__(name: str):
    module_name = _EXPORTS.get(name)
    if module_name is None:
        raise AttributeError(name)
    value = getattr(import_module(module_name), name)
    globals()[name] = value
    return value
