/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 KuiGang Wang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lt.compiler;

import lt.compiler.lexical.*;
import lt.compiler.syntactic.UnknownTokenException;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * transform plain <tt>Latte</tt> text into Tokens<br>
 * <tt>Latte</tt> uses indentation to differ program blocks<br>
 * Instead of using <tt>INDENT</tt> and <tt>DEDENT</tt>, the Scanner uses a <b>Tree of Tokens</b> to record the indentation info.
 * Consistent statements with the same indentation are in the same <b>Layer</b><br>
 * <pre>
 * class User
 *     id : int
 *     name : String
 * </pre>
 * <code>(id : int)</code> and <code>(name : String)</code> are in the same layer<br>
 * the text would be considered as the following token tree<br>
 * <pre>
 * [class]-[User]-[|]-[END]
 *                 |
 *                 --[id]-[:]-[int]-[END]-[name]-[:]-[String]
 * </pre>
 *
 * @see Node
 * @see Element
 * @see ElementStartNode
 * @see EndingNode
 */
public class IndentScanner extends AbstractScanner {
        /**
         * initiate the processor with a reader
         *
         * @param fileName   the input text file name
         * @param reader     text reader
         * @param properties properties for the Scanner
         * @param err        error manager
         */
        public IndentScanner(String fileName, Reader reader, Properties properties, ErrorManager err) {
                super(fileName, reader, properties, err);
        }

        /**
         * <ol>
         * <li>read a line from the {@link #reader}</li>
         * <li>check indentation</li>
         * <li>check layer =&gt; if indent &gt; lastLayerIndent, then start a new layer.
         * elseif indent &lt; lastLayerIndent then it should go back to upper layers<br>
         * </li>
         * <li>invoke {@link #scan(String, Args)} to scan the current line</li>
         * <li>append an {@link EndingNode}</li>
         * </ol>
         *
         * @param args args context
         * @throws IOException     exceptions during reading chars from reader
         * @throws SyntaxException syntax exceptions, including {@link SyntaxException}, {@link UnexpectedTokenException}, {@link IllegalIndentationException}
         * @see #scan(String, Args)
         */
        @Override
        protected void scan(Args args) throws IOException, SyntaxException {
                String line = reader.readLine();
                int rootIndent = -1;
                while (line != null) {
                        ++args.currentLine;

                        err.putLineRecord(args.fileName, args.currentLine, line);

                        args.currentCol = properties._COLUMN_BASE_;
                        args.useDefine.clear();

                        if (args.multipleLineComment) {
                                if (!line.contains(MultipleLineCommentEnd)) {
                                        line = reader.readLine();
                                        continue;
                                } else {
                                        int subCol = line.indexOf(MultipleLineCommentEnd) + MultipleLineCommentEnd.length();
                                        line = line.substring(subCol);
                                        args.currentCol += (subCol + 1);
                                        args.multipleLineComment = false;
                                }
                        }

                        // the line is nothing but comment
                        if (line.trim().startsWith(COMMENT)) {
                                line = reader.readLine();
                                continue;
                        }

                        int COMMENT_index = line.indexOf(COMMENT);
                        if (COMMENT_index != -1) {
                                String pre = line.substring(0, COMMENT_index);
                                String post = line.substring(COMMENT_index);
                                for (Map.Entry<String, String> definedEntry : args.defined.entrySet()) {
                                        String tmp = pre;
                                        pre = pre.replace(definedEntry.getKey(), definedEntry.getValue());
                                        if (!tmp.equals(pre)) {
                                                args.useDefine.put(definedEntry.getKey(), definedEntry.getValue());
                                        }
                                }
                                line = pre + post;
                        } else {
                                for (Map.Entry<String, String> definedEntry : args.defined.entrySet()) {
                                        String tmp = line;
                                        line = line.replace(definedEntry.getKey(), definedEntry.getValue());
                                        if (!tmp.equals(line)) {
                                                args.useDefine.put(definedEntry.getKey(), definedEntry.getValue());
                                        }
                                }
                        }

                        // get front spaces
                        int spaces = 0;
                        for (int i = 0; i < line.length(); ++i) {
                                if (line.charAt(i) != ' ') {
                                        spaces = i;
                                        break;
                                }
                        }

                        // set root indent
                        if (rootIndent == -1) {
                                rootIndent = spaces;
                                spaces = 0;
                        } else {
                                spaces -= rootIndent;
                        }

                        if (args.currentCol == properties._COLUMN_BASE_) {
                                args.currentCol += spaces + 1 + rootIndent;
                        }

                        int indentation = spaces;

                        // remove spaces
                        line = line.trim();

                        // check it's an empty line
                        if (line.isEmpty()) {
                                line = reader.readLine();
                                continue;
                        }

                        // check start node
                        ElementStartNode lastStartNode = args.startNodeStack.lastElement();
                        Indent lastIndentElem = lastStartNode.getIndent();
                        int lastNonFlexIndent = args.getLastNonFlexIndent();
                        if (indentation > lastNonFlexIndent && lastIndentElem.getIndent() == Indent.FLEX) {
                                // flex
                                ElementStartNode parent = args.startNodeStack.elementAt(args.startNodeStack.size() - 2);
                                Indent parentIndent = parent.getIndent();
                                if (indentation > parentIndent.getIndent()) {
                                        // greater indent, assign it to the flex
                                        lastIndentElem.setIndent(indentation);
                                } else {
                                        // smaller or equal
                                        // do redirect
                                        redirectToDeeperStartNodeByIndent(args, indentation, true);
                                }
                        } else {
                                if (lastIndentElem.getIndent() != indentation) {
                                        if (indentation <= lastNonFlexIndent) {
                                                // smaller indent
                                                // check PAIR_END and handle
                                                boolean isPairEnd = false;
                                                for (String pairEnd : PAIR.values()) {
                                                        if (line.startsWith(pairEnd)) {
                                                                isPairEnd = true;
                                                                PairEntry lastPair = args.pairEntryStack.lastElement();

                                                                // check indentation
                                                                ElementStartNode theStartNode = lastPair.startNode;
                                                                // go to it's parent
                                                                for (int i = args.startNodeStack.size() - 1; i >= 0; --i) {
                                                                        ElementStartNode node = args.startNodeStack.get(i);
                                                                        if (node == theStartNode) {
                                                                                assert i != 0;
                                                                                theStartNode = args.startNodeStack.get(i - 1);
                                                                                break;
                                                                        }
                                                                }
                                                                Indent indent = theStartNode.getIndent();
                                                                if (indent.getIndent() != Indent.FLEX) {
                                                                        // current should greater or equal
                                                                        // else, raise compile error
                                                                        if (indentation < indent.getIndent()) {
                                                                                err.IllegalIndentationException(indent.getIndent(), args.generateLineCol());
                                                                                // error handling: ignore and assume it's correct
                                                                        }
                                                                }
                                                                if (!PAIR.get(lastPair.key).equals(pairEnd)) {
                                                                        // last pair mismatch
                                                                        // set `isPairEnd` to false
                                                                        // and throw compile error
                                                                        // in later steps
                                                                        isPairEnd = false;
                                                                }
                                                                break;
                                                        }
                                                }
                                                // if is  PAIR_END, handle the redirect in later steps
                                                // if not PAIR_END, redirect the startNode
                                                if (!isPairEnd) {
                                                        redirectToDeeperStartNodeByIndent(args, indentation, true);
                                                }
                                        } else { // if (lastIndent > indentation) {
                                                // greater indent
                                                createStartNode(args, indentation);
                                        }
                                }
                        }

                        // start parsing
                        scan(line, args);

                        if (!args.multipleLineComment) {

                                if (args.previous instanceof Element) {
                                        args.previous = new EndingNode(args, EndingNode.WEAK);
                                }
                        }

                        line = reader.readLine();
                }
        }

        /**
         * when the given line is not empty, do scanning.<br>
         * <ol>
         * <li>check whether the line contains tokens in {@link #SPLIT}<br>
         * get the most front token, if several tokens are at the same position, then choose the longest one</li>
         * <li>if the token not found, consider the whole line as one element and append to previous node</li>
         * <li>else</li>
         * <li>record text before the token as an element and do appending</li>
         * <li>check which category the token is in<br>
         * <ul>
         * <li>{@link #LAYER} means starts after recording the token, a new ElementStartNode should be started, invoke {@link #createStartNode(Args, int)}</li>
         * <li>{@link #SPLIT_X}: the previous element and next element should be in the same layer. if it's also among {@link #NO_RECORD}, the token won't be recorded</li>
         * <li>{@link #STRING}: the next element is a string or a character. these characters should be considered as one element</li>
         * <li>{@link #ENDING}: append a new {@link EndingNode} to prevent generated nodes being ambiguous. NOTE that it only means the end of an expression or a statement. not the parsing process</li>
         * <li>{@link #COMMENT}: it's the start of a comment. the following chars would be ignored</li>
         * <li>{@link #PAIR}: for keys, it will start a new layer. for values, it will end the layer created by the key</li>
         * </ul>
         * </li>
         * </ol><br>
         * here's an example of how the method works<br>
         * given the following input:<br>
         * <pre>
         * val map={'name':'cass'}
         * </pre>
         * <ol>
         * <li>the most front and longest token is ' ', and ' ' is in {@link #NO_RECORD} ::: <code>val/map={'name':'cass'}</code></li>
         * <li>the most front and longest token is '=' ::: <code>val/map/=/{'name':'cass'}</code></li>
         * <li>the most front and longest token is '{', and '{' is a key of {@link #PAIR} ::: <code>val/map/=/{/(LAYER-START/'name':'cass'})</code></li>
         * <li>the most front and longest token is "'", and "'" is in {@link #STRING} ::: <code>val/map/=/{/(LAYER-START/'name'/:'cass'})</code></li>
         * <li>the most front and longest token is ':' ::: <code>val/map/=/{/(LAYER-START/'name'/:/'cass'})</code></li>
         * <li>the most front and longest token is "'", and "'" is in {@link #STRING} ::: <code>val/map/=/{/(LAYER-START/'name'/:/'cass'/})</code></li>
         * <li>the most front and longest token is "}", and '}' is a value of {@link #PAIR} ::: <code>val/map/=/{/(LAYER-START/'name'/:/'cass')}</code></li>
         * </ol><br>
         * the result is <code>val/map/=/{/(LAYER-START/'name'/:/'cass')}</code><br>
         * set a breakpoint in the method and focus on variable <tt>line</tt>, you will get exactly the same intermediate results
         *
         * @param line line to parse
         * @param args args context
         * @throws SyntaxException syntax exceptions, including {@link SyntaxException}, {@link UnexpectedTokenException}
         */
        private void scan(String line, Args args) throws SyntaxException {
                if (line.isEmpty()) return;

                // check multiple line comment
                if (args.multipleLineComment) {
                        if (line.contains(MultipleLineCommentEnd)) {
                                int subCol = line.indexOf(MultipleLineCommentEnd) + MultipleLineCommentEnd.length();
                                args.currentCol += subCol;
                                line = line.substring(subCol);
                                args.multipleLineComment = false;
                        } else {
                                return;
                        }
                }

                // check SPLIT
                // find the pattern at minimum location index and with longest words
                int minIndex = line.length();
                String token = null; // recorded token
                for (String s : SPLIT) {
                        if (line.contains(s)) {
                                int index = line.indexOf(s);
                                if (index != -1 && index < minIndex) {
                                        minIndex = index;
                                        token = s;
                                }
                        }
                }

                if (token == null) {
                        if (!line.isEmpty()) {
                                // not found, simply append whole input to previous
                                TokenType type = getTokenType(line, args.generateLineCol());
                                if (type != null) {
                                        // unknown token, ignore this token
                                        args.previous = new Element(args, line, type);
                                        args.currentCol += line.length();
                                }
                        }
                } else {
                        String copyOfLine = line;
                        String str = line.substring(0, minIndex);
                        if (!str.isEmpty()) {
                                // record text before the token
                                TokenType type = getTokenType(str, args.generateLineCol());
                                if (type != null) {
                                        args.previous = new Element(args, str, type);
                                }
                                args.currentCol += str.length();
                        }

                        if (LAYER.contains(token)) {
                                // start new layer
                                args.previous = new Element(args, token, getTokenType(token, args.generateLineCol()));
                                createStartNode(args, Indent.FLEX);
                        } else if (SPLIT_X.contains(token)) {
                                // do split check
                                if (!NO_RECORD.contains(token)) {
                                        // record this token
                                        args.previous = new Element(args, token, getTokenType(token, args.generateLineCol()));
                                }
                        } else if (STRING.contains(token)) {
                                // string literal
                                int lastIndex = minIndex;
                                while (true) {
                                        int index = line.indexOf(token, lastIndex + token.length());
                                        if (token.equals("//")) {
                                                while (line.length() > index + 2) {
                                                        if (line.charAt(index + 2) == '/') {
                                                                ++index;
                                                        } else {
                                                                break;
                                                        }
                                                }
                                        }
                                        if (line.length() <= 1 || index == -1) {
                                                err.SyntaxException("end of string not found", args.generateLineCol());
                                                // assume that the end is line end
                                                err.debug("assume that the " + token + " end is line end");

                                                String generated = line.substring(minIndex) + token;

                                                args.previous = new Element(args, generated, getTokenType(generated, args.generateLineCol()));
                                                args.currentCol += (index - minIndex) - token.length(); // the length would be added in later steps
                                                line = line.substring(index + 1);

                                                break;
                                        } else {
                                                String c = String.valueOf(line.charAt(index - 1));
                                                // check
                                                boolean isStringEnd = !ESCAPE.equals(c) || checkStringEnd(line, index - 1);

                                                if (isStringEnd) {
                                                        // the string starts at minIndex and ends at index
                                                        String s = line.substring(minIndex, index + token.length());

                                                        args.previous = new Element(args, s, getTokenType(s, args.generateLineCol()));
                                                        args.currentCol += (index - minIndex);
                                                        line = line.substring(index + token.length());
                                                        break;
                                                }

                                                lastIndex = index;
                                        }
                                }
                        } else if (ENDING.contains(token)) {
                                // ending
                                if (args.previous instanceof Element) {
                                        args.previous = new EndingNode(args, EndingNode.STRONG);
                                }
                        } else if (COMMENT.equals(token)) {
                                // comment
                                line = ""; // ignore all
                        } else if (PAIR.containsKey(token)) {
                                // pair start
                                args.previous = new Element(args, token, getTokenType(token, args.generateLineCol()));
                                createStartNode(args, Indent.FLEX);
                                args.pairEntryStack.push(new PairEntry(token, args.startNodeStack.lastElement()));
                        } else if (PAIR.containsValue(token)) {
                                // pair end
                                if (args.pairEntryStack.isEmpty()) {
                                        err.UnexpectedTokenException(token, args.generateLineCol());
                                        return;
                                }
                                PairEntry entry = args.pairEntryStack.pop();
                                String start = entry.key;
                                if (!token.equals(PAIR.get(start))) {
                                        err.UnexpectedTokenException(PAIR.get(start), token, args.generateLineCol());
                                        // assume that the pair ends
                                        err.debug("assume that the pair ends");
                                }

                                ElementStartNode pairStartNode = entry.startNode;
                                if (pairStartNode.hasNext()) {
                                        if (pairStartNode.next() instanceof EndingNode && !pairStartNode.next().hasNext()) {
                                                pairStartNode.setNext(null);
                                        } else {
                                                err.SyntaxException(
                                                        "indentation of " + pairStartNode.next() + " should be " + pairStartNode.getIndent(),
                                                        pairStartNode.next().getLineCol());
                                                // fill the LinkedNode with all nodes after the pairStartNode
                                                Node n = pairStartNode.next();
                                                n.setPrevious(null);
                                                pairStartNode.setNext(null);
                                                pairStartNode.setLinkedNode(n);
                                        }
                                }

                                ElementStartNode lastElement = args.startNodeStack.lastElement();
                                Indent lastIndentElem = lastElement.getIndent();
                                int lastIndent = lastIndentElem.getIndent();
                                int pairIndent = pairStartNode.getIndent().getIndent();
                                if (lastIndent >= pairIndent) {
                                        redirectToPairStart(args, pairStartNode.getIndent());
                                } else {
                                        args.previous = pairStartNode;
                                }
                                args.previous = new Element(args, PAIR.get(start), getTokenType(token, args.generateLineCol()));
                        } else if (token.equals(MultipleLineCommentStart)) {
                                if (!args.multipleLineComment) {
                                        args.multipleLineComment = true;
                                }
                        } else {
                                err.UnknownTokenException(token, args.generateLineCol());
                                // unknown token
                                // simply ignore the token
                        }

                        // column
                        args.currentCol += token.length();
                        if (copyOfLine.equals(line)) {
                                // line hasn't changed, do default modification
                                line = line.substring(minIndex + token.length());
                        }
                        // recursively parse
                        scan(line, args);
                }
        }

        @Override
        protected void finalCheck(ElementStartNode root) throws UnknownTokenException {
                super.finalCheck(root);

                if (root.hasLinkedNode()) {
                        Node n = root.getLinkedNode();
                        // remove redundant start node
                        if (!n.hasNext() && n instanceof ElementStartNode) {
                                Node newN = ((ElementStartNode) n).getLinkedNode();
                                root.setLinkedNode(newN);
                                n = newN;
                        }

                        while (n != null) {
                                if (n instanceof Element) {
                                        // remove {...} without `:` between them and not empty
                                        if (((Element) n).getContent().equals("{")) {
                                                Node afterBraceStart = n.next();
                                                if (!(afterBraceStart instanceof Element)
                                                        || !((Element) afterBraceStart).getContent().equals("}")) {
                                                        assert afterBraceStart instanceof ElementStartNode;
                                                        removeLayerControlSymbols(root, (Element) n);
                                                        while (true) {
                                                                n = n.next();
                                                                if (n instanceof Element
                                                                        && ((Element) n).getContent().equals("}")) {
                                                                        removeLayerControlSymbols(root, (Element) n);
                                                                        break;
                                                                }
                                                        }
                                                }
                                        }
                                }
                                n = n.next();
                        }

                }
        }

        private void removeLayerControlSymbols(ElementStartNode root, Element n) {

                if (n.hasPrevious()) {
                        if (n.previous().previous() != null
                                && n.previous() instanceof EndingNode
                                && n.next() == null) {
                                // remove the previous ending node
                                n.previous().previous().setNext(null);
                        } else {
                                n.previous().setNext(n.next());
                        }
                } else if (n.getContent().equals("{")) {
                        root.setLinkedNode(n.next());
                }
                if (n.hasNext()) {
                        n.next().setPrevious(n.previous());
                }
        }
}
