import sys
import traceback


def usage():
    print('Usage: wsadmin -lang jython -f configure-javaagent.py add|remove <cell> <node> <server> <agentSpecOrJarPath>')


def normalize_path(value):
    if value is None:
        return ''
    return value.replace('\\', '/').lower()


def normalize_jvm_args_value(value):
    if value is None:
        return ''
    return value.replace('\\\\', '\\')


def split_jvm_args(value):
    if value is None:
        return []
    tokens = []
    token = ''
    quote = None
    index = 0
    while index < len(value):
        current = value[index]
        if quote is None and (current == '"' or current == "'"):
            quote = current
            token = token + current
        elif quote is not None and current == quote:
            quote = None
            token = token + current
        elif quote is None and current.isspace():
            if token:
                tokens.append(token)
                token = ''
        else:
            token = token + current
        index = index + 1
    if token:
        tokens.append(token)
    return tokens


def extract_agent_jar(token):
    if token is None or not token.startswith('-javaagent:'):
        return None
    value = token[len('-javaagent:'):]
    separator = value.find('=')
    if separator >= 0:
        return value[:separator]
    return value


def remove_matching_javaagents(tokens, agent_jar_path):
    filtered = []
    normalized_target = normalize_path(agent_jar_path)
    for token in tokens:
        current_jar = extract_agent_jar(token)
        if current_jar is not None and normalize_path(current_jar) == normalized_target:
            continue
        filtered.append(token)
    return filtered


def join_jvm_args(tokens):
    return ' '.join(tokens)


def find_server(cell, node, server):
    return AdminConfig.getid('/Cell:%s/Node:%s/Server:%s/' % (cell, node, server))


def require_jvm(server_id):
    jvm_id = AdminConfig.list('JavaVirtualMachine', server_id)
    if not jvm_id:
        print('No JavaVirtualMachine found for server configuration: %s' % server_id)
        sys.exit(2)
    return jvm_id


def add_agent(cell, node, server, agent_spec):
    server_id = find_server(cell, node, server)
    if not server_id:
        print('Server not found: cell=%s node=%s server=%s' % (cell, node, server))
        sys.exit(2)

    jvm_id = require_jvm(server_id)
    current_args = normalize_jvm_args_value(AdminConfig.showAttribute(jvm_id, 'genericJvmArguments'))
    current_tokens = split_jvm_args(current_args)
    agent_jar = extract_agent_jar(agent_spec)
    updated_tokens = remove_matching_javaagents(current_tokens, agent_jar)
    updated_tokens.append(agent_spec)
    updated_args = join_jvm_args(updated_tokens)

    if updated_args == (current_args or ''):
        print('Generic JVM arguments already contain the requested javaagent configuration.')
        return

    AdminConfig.modify(jvm_id, [['genericJvmArguments', updated_args]])
    AdminConfig.save()
    print('Added javaagent configuration to %s/%s/%s' % (cell, node, server))
    print(updated_args)


def remove_agent(cell, node, server, agent_jar_path):
    server_id = find_server(cell, node, server)
    if not server_id:
        print('Server not found: cell=%s node=%s server=%s' % (cell, node, server))
        sys.exit(2)

    jvm_id = require_jvm(server_id)
    current_args = normalize_jvm_args_value(AdminConfig.showAttribute(jvm_id, 'genericJvmArguments'))
    current_tokens = split_jvm_args(current_args)
    updated_tokens = remove_matching_javaagents(current_tokens, agent_jar_path)
    updated_args = join_jvm_args(updated_tokens)

    if updated_args == (current_args or ''):
        print('No matching javaagent configuration found for %s' % agent_jar_path)
        return

    AdminConfig.modify(jvm_id, [['genericJvmArguments', updated_args]])
    AdminConfig.save()
    print('Removed javaagent configuration from %s/%s/%s' % (cell, node, server))
    print(updated_args)


def main(argv):
    if len(argv) != 5:
        usage()
        sys.exit(1)

    action = argv[0]
    cell = argv[1]
    node = argv[2]
    server = argv[3]
    value = argv[4]

    if action == 'add':
        add_agent(cell, node, server, value)
    elif action == 'remove':
        remove_agent(cell, node, server, value)
    else:
        usage()
        sys.exit(1)


try:
    main(sys.argv)
except SystemExit:
    raise
except:
    print('wsadmin javaagent update failed.')
    print('Arguments: %s' % list(sys.argv))
    traceback.print_exc()
    sys.exit(2)