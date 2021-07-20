import json
import tempfile

import pytest
from graphviz import Digraph

from oso import Oso, Variable, Predicate


class User:
    def __init__(self, id):
        self.id = id


class Resource:
    def __init__(self, id, user):
        self.id = id
        self.user = user

    def get_user(self):
        return self.user


def build_trace_file():
    oso = Oso()
    oso.load_str("f(1); f(2);")
    oso.register_class(User)
    oso.register_class(Resource)

    user = User(1)
    resource = Resource(2, user)

    with tempfile.NamedTemporaryFile(suffix=".polar") as f:
        f.write(
            """
            allow(actor: User, action, resource: Resource) if actor.id == resource.id;
            allow(actor: User, action, resource: Resource) if resource.id = 1;
            allow(actor: User, action, resource: Resource) if resource.get_user() = actor;
        """.encode(
                "ascii"
            )
        )
        f.flush()

        oso.load_file(f.name)
        query = oso._query(Predicate("allow", (user, "read", resource)))

        results = [r for r in query.run()]

        trace = query.trace()
        with open("trace.json", "w") as fw:
            json.dump(trace, fw)


# def test_construct_d3_data():
#     build_trace_file()
#     d3_map = {}
#     with open("trace.json") as f:
#         data = json.load(f)
#         for node in data:
#             if node["id"] == node["parent_id"]
#             pass


def test_graph():
    build_trace_file()
    event_map = {
        "ChoicePush": "blue",
        "ExecuteGoal": "yellow",
        "EvaluateRule": "purple",
        "ExecuteChoice": "orange",
        "Bindings": "purple",
        "Backtrack": "red",
        "Result": "green",
        "Done": "black",
    }
    hidden = ["Bindings", "ChoicePush", "ExecuteChoice"]
    node_map = {}
    dot = Digraph(comment="Trace graph")
    with open("trace.json") as f:
        data = json.load(f)
        for node in data:
            node_map[node["id"]] = node
            event_type = node["event_type"]
            color = event_map[event_type]
            name = str(node["id"])
            label = event_type
            if event_type == "ExecuteGoal":
                label = "QUERY: " + node["goal"]["polar"]
            elif event_type == "EvaluateRule":
                label = "RULE: " + node["rule"]
            elif event_type == "Backtrack":
                label = "BACKTRACK: " + node["reason"]
            elif event_type == "Result":
                if node["bindings"]:
                    label = "RESULT: " + str(node["bindings"])
                else:
                    label = "SUCCESS"
            if event_type not in hidden:
                dot.node(name, label=label, color=color)

        for node in data:
            if node["event_type"] not in hidden:
                parent_id = node["parent_id"]
                parent_node = node_map[parent_id]
                while parent_node["event_type"] in hidden:
                    parent_node = node_map[parent_node["parent_id"]]
                    parent_id = parent_node["id"]
                id = node["id"]
                if parent_id != id:
                    dot.edge(str(parent_id), str(id))

    dot.render("trace.gv", view=True, format="svg")