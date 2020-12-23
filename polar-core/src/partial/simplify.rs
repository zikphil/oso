use std::collections::{HashMap, HashSet};

use crate::folder::{fold_operation, fold_term, Folder};
use crate::formatting::ToPolarString;
use crate::kb::Bindings;
// use crate::terms::{Operation, Operator, Symbol, Term, TermList, Value};
use crate::terms::{Operation, Operator, Symbol, Term, Value};

/// A trivially true expression.
const TRUE: Operation = op!(And);
/// A trivially false expression.
const FALSE: Operation = op!(Or);

/// Invert operators.
fn invert_operation(Operation { operator, args }: Operation) -> Operation {
    fn invert_args(args: Vec<Term>) -> Vec<Term> {
        args.into_iter()
            .map(|t| {
                t.clone_with_value(value!(invert_operation(
                    t.value().as_expression().unwrap().clone()
                )))
            })
            .collect()
    }

    match operator {
        Operator::And => Operation {
            operator: Operator::Or,
            args: invert_args(args),
        },
        Operator::Or => Operation {
            operator: Operator::And,
            args: invert_args(args),
        },
        Operator::Unify | Operator::Eq => Operation {
            operator: Operator::Neq,
            args,
        },
        Operator::Neq => Operation {
            operator: Operator::Unify,
            args,
        },
        Operator::Gt => Operation {
            operator: Operator::Leq,
            args,
        },
        Operator::Geq => Operation {
            operator: Operator::Lt,
            args,
        },
        Operator::Lt => Operation {
            operator: Operator::Geq,
            args,
        },
        Operator::Leq => Operation {
            operator: Operator::Gt,
            args,
        },
        Operator::Debug | Operator::Print | Operator::New | Operator::Dot => {
            Operation { operator, args }
        }
        Operator::Isa => Operation {
            operator: Operator::Not,
            args: vec![term!(op!(Isa, args[0].clone(), args[1].clone()))],
        },
        Operator::Not => args[0]
            .value()
            .as_expression()
            .expect("negated expression")
            .clone(),
        _ => todo!("negate {:?}", operator),
    }
}

struct VariableSubber {
    this_var: Symbol,
}

impl VariableSubber {
    pub fn new(this_var: Symbol) -> Self {
        Self { this_var }
    }
}

impl Folder for VariableSubber {
    fn fold_variable(&mut self, v: Symbol) -> Symbol {
        if v == self.this_var {
            sym!("_this")
        } else {
            v
        }
    }
}

/// Substitute `sym!("_this")` for a variable in a partial.
pub fn sub_this(this: Symbol, term: Term) -> Term {
    eprintln!("THIS: {}; TERM: {}", this, term.to_polar());
    if term
        .value()
        .as_symbol()
        .map(|s| s == &this)
        .unwrap_or(false)
    {
        return term;
    }
    fold_term(term, &mut VariableSubber::new(this))
}

/// Turn `_this = x` into `x`.
fn simplify_trivial_constraint(this: Symbol, term: Term) -> Term {
    match term.value() {
        Value::Expression(o) if o.operator == Operator::Unify => {
            let left = &o.args[0];
            let right = &o.args[1];
            match (left.value(), right.value()) {
                (Value::Variable(v), Value::Variable(w)) if v == &this && w == &this => {
                    unreachable!()
                }
                (Value::Variable(v), _) if v == &this => right.clone(),
                (_, Value::Variable(v)) if v == &this => left.clone(),
                _ => term,
            }
        }
        _ => term,
    }
}

/// Simplify the values of the bindings to be returned to the host language.
///
/// - For partials, simplify the constraint expressions.
/// - For non-partials, deep deref.
/// TODO(ap): deep deref.
pub fn simplify_bindings(bindings: Bindings) -> Option<Bindings> {
    let mut unsatisfiable = false;
    let mut simplify = |var: Symbol, term: Term| {
        let mut simplifier = Simplifier::new(var.clone());
        let simplified = simplifier.simplify_partial(term);
        let simplified = simplify_trivial_constraint(var.clone(), simplified);
        let simplified = sub_this(var, simplified);
        match simplified.value().as_expression() {
            Ok(o) if o == &FALSE => unsatisfiable = true,
            _ => (),
        }
        simplified
    };

    let bindings: Bindings = bindings
        .iter()
        .filter(|(var, _)| !var.is_temporary_var())
        .map(|(var, value)| match value.value() {
            Value::Expression(o) => {
                assert_eq!(o.operator, Operator::And);
                (var.clone(), simplify(var.clone(), value.clone()))
            }
            Value::Variable(v) if v.is_temporary_var() => {
                (var.clone(), simplify(var.clone(), bindings[v].clone()))
            }
            _ => (var.clone(), value.clone()),
        })
        .collect();

    if unsatisfiable {
        None
    } else {
        Some(bindings)
    }
}

pub struct Simplifier {
    bindings: Bindings,
    this_var: Symbol,
}

// Look for incompatible unifications.
pub fn is_coherent(o: &Operation) -> bool {
    let mut seen: HashMap<Symbol, Value> = HashMap::new();
    for c in o.constraints().iter() {
        if c.operator != Operator::Unify {
            continue;
        }
        let left = &c.args[0];
        let right = &c.args[1];
        match (left.value(), right.value()) {
            (Value::Variable(v), x) | (x, Value::Variable(v)) if x.is_ground() => {
                if let Some(y) = seen.insert(v.clone(), x.clone()) {
                    if &y != x {
                        return false;
                    }
                }
            }
            _ => continue,
        }
    }
    true
}

impl Folder for Simplifier {
    fn fold_term(&mut self, t: Term) -> Term {
        fold_term(self.deref(&t), self)
    }

    fn fold_operation(&mut self, mut o: Operation) -> Operation {
        if o.operator == Operator::And {
            // Preprocess constraints.
            let mut seen: HashSet<&Operation> = HashSet::new();
            o = o.clone_with_constraints(
                o.constraints()
                    .iter()
                    .filter(|o| *o != &TRUE) // Drop empty constraints.
                    .filter(|o| seen.insert(o)) // Deduplicate constraints.
                    .cloned()
                    .collect(),
            );
        }

        match o.operator {
            // Zero-argument conjunctions & disjunctions represent constants
            // TRUE and FALSE, respectively. We do not simplify them.
            Operator::And | Operator::Or if o.args.is_empty() => o,

            // Replace one-argument conjunctions & disjunctions with their argument.
            Operator::And | Operator::Or if o.args.len() == 1 => fold_operation(
                o.args[0]
                    .value()
                    .as_expression()
                    .expect("expression")
                    .clone(),
                self,
            ),

            // A trivial unification is always TRUE.
            Operator::Unify | Operator::Eq if o.args[0] == o.args[1] => TRUE,

            Operator::And if o.args.len() > 1 => {
                if !is_coherent(&o) {
                    return FALSE;
                }

                // Toss trivial unifications.
                for (i, c) in o.constraints().into_iter().enumerate() {
                    if c.operator == Operator::Unify && c.args.len() == 2 && c.args[0] == c.args[1]
                    {
                        eprintln!("TOSSING CONSTRAINT `{}`", o.args[i].to_polar());
                        o.args.remove(i);
                    }
                }

                // Choose an (anti)unification constraint to make a binding from, maybe throw it
                // away, and fold the rest.
                if let Some(i) = o.constraints().iter().position(|o| match o.operator {
                    Operator::Unify | Operator::Neq => {
                        let left = &o.args[0];
                        let right = &o.args[1];
                        let invert = o.operator == Operator::Neq;
                        left == right
                            || match (left.value(), right.value()) {
                                (Value::Variable(v), x)
                                    if !self.is_this_var(left)
                                        && !self.is_bound(v)
                                        && x.is_ground() =>
                                {
                                    eprintln!("A {} ← {}", left.to_polar(), right.to_polar());
                                    self.bind(v.clone(), right.clone(), invert);
                                    true
                                }
                                (x, Value::Variable(v))
                                    if !self.is_this_var(right)
                                        && !self.is_bound(v)
                                        && x.is_ground() =>
                                {
                                    eprintln!("B {} ← {}", right.to_polar(), left.to_polar());
                                    self.bind(v.clone(), left.clone(), invert);
                                    true
                                }
                                (_, Value::Variable(v)) if self.is_this_var(left) => {
                                    eprintln!("C {} ← {}", right.to_polar(), left.to_polar());
                                    self.bind(v.clone(), left.clone(), invert);
                                    false
                                }
                                (Value::Variable(v), _) if self.is_this_var(right) => {
                                    eprintln!("D {} ← {}", left.to_polar(), right.to_polar());
                                    self.bind(v.clone(), right.clone(), invert);
                                    false
                                }
                                _ => false,
                            }
                    }
                    _ => false,
                }) {
                    eprintln!("CHOSEN CONSTRAINT: {}", &o.args[i].to_polar());
                    o.args.remove(i);
                }
                fold_operation(o, self)
            }

            // (Negated) comparisons.
            Operator::Eq | Operator::Gt | Operator::Geq | Operator::Lt | Operator::Leq => {
                let left = &o.args[0];
                let right = &o.args[1];
                match (left.value().as_expression(), right.value().as_expression()) {
                    (Ok(left), Ok(right))
                        if left.operator == Operator::Not && right.operator == Operator::Not =>
                    {
                        todo!("not x = not y");
                    }
                    (Ok(left), _) if left.operator == Operator::Not => {
                        invert_operation(Operation {
                            operator: o.operator,
                            args: vec![left.args[0].clone(), right.clone()],
                        })
                    }
                    (_, Ok(right)) if right.operator == Operator::Not => {
                        invert_operation(Operation {
                            operator: o.operator,
                            args: vec![left.clone(), right.args[0].clone()],
                        })
                    }
                    _ => fold_operation(o, self),
                }
            }

            // Negation.
            Operator::Not => fold_operation(
                invert_operation(
                    self.simplify_partial(o.args[0].clone())
                        .value()
                        .as_expression()
                        .expect("expression")
                        .clone(),
                ),
                self,
            ),
            _ => fold_operation(o, self),
        }
    }
}

impl Simplifier {
    pub fn new(this_var: Symbol) -> Self {
        Self {
            this_var,
            bindings: Bindings::new(),
        }
    }

    pub fn bind(&mut self, var: Symbol, value: Term, invert: bool) {
        self.bindings.insert(
            var,
            if invert {
                value.clone_with_value(value!(op!(Not, value.clone())))
            } else {
                value
            },
        );
    }

    pub fn deref(&self, term: &Term) -> Term {
        match term.value() {
            // TODO(gj): RestVariable?
            Value::Variable(symbol) => self.bindings.get(symbol).unwrap_or(term).clone(),
            _ => term.clone(),
        }
    }

    fn is_bound(&self, var: &Symbol) -> bool {
        self.bindings.contains_key(var)
    }

    fn is_this_var(&self, t: &Term) -> bool {
        match t.value() {
            Value::Variable(v) => v == &self.this_var,
            Value::Expression(Operation {
                operator: Operator::Dot,
                args,
            }) => self.is_this_var(&args[0]),
            _ => false,
        }
    }

    /// Simplify a partial until quiescence.
    fn simplify_partial(&mut self, mut term: Term) -> Term {
        let mut new;
        loop {
            eprintln!("SIMPLIFYING {}: {}", self.this_var, term.to_polar());
            new = self.fold_term(term.clone());
            eprintln!(" ⇒ {}", new.to_polar());
            if new == term {
                break;
            }
            term = new;
        }
        new
    }
}